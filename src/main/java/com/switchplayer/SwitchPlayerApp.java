package com.switchplayer;

import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioSystem;
import org.lwjgl.glfw.GLFWImage;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;


import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SwitchPlayer - player de baixa latencia para placas de captura UVC (DirectShow).
 *
 * Thread "capture": FFmpeg/dshow -> copia off-heap -> FrameExchange (lock-free)
 * Thread principal (render): GLFW + OpenGL 3.3 core -> textura -> shader CAS -> swap vsync
 *
 * Teclas: ESC sai | S liga/desliga nitidez | CIMA/BAIXO ajusta intensidade
 */
public final class SwitchPlayerApp {

    // ------------------------------------------------------------------ config

    record Config(String device, String audioDevice, String outputMixer, int width, int height, int fps, boolean mjpeg,
                  float sharp, float radius, float saturation, boolean fxaa, String scaleMode, boolean aiEnabled, String gpuBackend, boolean list, boolean modes, boolean debug) {

        static Config parse(String[] a) {
            String device = "USB Video";
            String audioDevice = "";
            String outputMixer = "";
            int w = 1920, h = 1080, fps = 60;
            boolean mjpeg = false, list = false, modes = false, debug = false;
            float sharp = 0.6f, radius = 1.0f, saturation = 1.10f;
            boolean fxaa = false; String scaleMode = "Preservar proporção"; boolean aiEnabled = false; String gpuBackend = "Automático (recomendado)";
            for (int i = 0; i < a.length; i++) {
                switch (a[i]) {
                    case "--device" -> device = a[++i];
                    case "--audio-device" -> audioDevice = a[++i];
                    case "--output-mixer" -> outputMixer = a[++i];
                    case "--size" -> {
                        String[] p = a[++i].toLowerCase().split("x");
                        w = Integer.parseInt(p[0]);
                        h = Integer.parseInt(p[1]);
                    }
                    case "--fps" -> fps = Integer.parseInt(a[++i]);
                    case "--sharp" -> sharp = Float.parseFloat(a[++i]);
                    case "--radius" -> radius = Float.parseFloat(a[++i]);
                    case "--saturation" -> saturation = Float.parseFloat(a[++i]);
                    case "--fxaa" -> fxaa = true;
                    case "--scale" -> scaleMode = a[++i];
                    case "--ai" -> aiEnabled = true;
                    case "--gpu" -> gpuBackend = a[++i];
                    case "--mjpeg" -> mjpeg = true;
                    case "--list" -> list = true;
                    case "--modes" -> modes = true;
                    case "--debug" -> debug = true;
                    default -> System.err.println("Argumento ignorado: " + a[i]);
                }
            }
            return new Config(device, audioDevice, outputMixer, w, h, fps, mjpeg, sharp, radius, saturation, fxaa, scaleMode, aiEnabled, gpuBackend, list, modes, debug);
        }

        String dshowInput() {
            if (device.equalsIgnoreCase("desktop")) return "desktop";
            String video = device.startsWith("video=") ? device.substring(6) : device;
            if (audioDevice == null || audioDevice.isBlank() || audioDevice.equals("(Sem Áudio)")) return "video=" + video;
            String audio = audioDevice.startsWith("audio=") ? audioDevice.substring(6) : audioDevice;
            return "video=" + video + ":audio=" + audio;
        }
    }

    // ------------------------------------------- troca de frames sem lock (triple buffer)

    /** Um buffer nativo (off-heap) + numero de sequencia. */
    static final class Slot {
        final ByteBuffer buf;
        volatile long seq;

        Slot(int size) {
            buf = MemoryUtil.memAlloc(size);
        }
    }

    /**
     * Triple buffer: produtor escreve em "back", publica trocando com "middle" (AtomicReference);
     * consumidor troca "front" com "middle" quando ha frame mais novo. Nunca bloqueia nem
     * tem leitura/escrita simultanea no mesmo buffer; frames atrasados sao descartados.
     */
    static final class FrameExchange {
        final int width, height, bytes;
        private final AtomicReference<Slot> middle;
        private Slot back;   // so o produtor toca
        private Slot front;  // so o consumidor toca
        private long counter;

        FrameExchange(int width, int height) {
            this.width = width;
            this.height = height;
            this.bytes = width * height * 3;
            back = new Slot(bytes);
            front = new Slot(bytes);
            middle = new AtomicReference<>(new Slot(bytes));
        }

        ByteBuffer writeBuffer() {
            return back.buf;
        }

        void publish() {
            back.seq = ++counter;
            back = middle.getAndSet(back);
        }

        /** Retorna o buffer do frame mais recente se for novo; senao null. */
        ByteBuffer acquireLatest() {
            if (middle.get().seq > front.seq) {
                front = middle.getAndSet(front);
                return front.buf;
            }
            return null;
        }

        void free() {
            MemoryUtil.memFree(back.buf);
            MemoryUtil.memFree(front.buf);
            MemoryUtil.memFree(middle.get().buf);
        }
    }

    // ------------------------------------------------------------------ shaders

    private static final String VERT = """
            #version 330 core
            out vec2 vUV;
            void main() {
                // Fullscreen triangle gerado so com gl_VertexID (sem VBO)
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUV = vec2(p.x, 1.0 - p.y);          // linha 0 da captura = topo da tela
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** Contrast Adaptive Sharpening (5 taps, estilo AMD FidelityFX CAS). */
    private static final String FRAG = """
            #version 330 core
            in vec2 vUV;
            out vec4 outColor;
            uniform sampler2D uTex;
            uniform vec2  uTexel;   // 1/largura, 1/altura da textura
            uniform float uSharp;   // 0 = desligado ... 1 = maximo
            uniform float uRadius;  // raio em texels (1.0 padrao)
            uniform float uSaturation;
            uniform int uFxaa;
            uniform vec2 uUvScale;
            void main() {
                vec2 uv = (vUV - 0.5) * uUvScale + 0.5;
                vec3 e = texture(uTex, uv).rgb;
                if (uSharp <= 0.0) { outColor = vec4(e, 1.0); return; }

                vec2 o = uTexel * uRadius;
                vec3 b = texture(uTex, uv + vec2(0.0, -o.y)).rgb;
                vec3 d = texture(uTex, uv + vec2(-o.x, 0.0)).rgb;
                vec3 f = texture(uTex, uv + vec2( o.x, 0.0)).rgb;
                vec3 h = texture(uTex, uv + vec2(0.0,  o.y)).rgb;

                vec3 mn = min(min(min(d, e), min(f, b)), h);
                vec3 mx = max(max(max(d, e), max(f, b)), h);

                // Peso adaptativo: menos nitidez onde ja ha alto contraste (evita halos)
                vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
                float peak = -1.0 / mix(8.0, 5.0, uSharp);
                vec3 w = amp * peak;
                vec3 rcpW = 1.0 / (1.0 + 4.0 * w);

                vec3 col = (b * w + d * w + f * w + h * w + e) * rcpW;
                if (uFxaa == 1) {
                    vec3 n = texture(uTex, uv + vec2(0.0, -uTexel.y)).rgb;
                    vec3 s = texture(uTex, uv + vec2(0.0, uTexel.y)).rgb;
                    vec3 wv = texture(uTex, uv + vec2(-uTexel.x, 0.0)).rgb;
                    vec3 ev = texture(uTex, uv + vec2(uTexel.x, 0.0)).rgb;
                    float edge = max(max(abs(dot(n-e, vec3(0.299,0.587,0.114))), abs(dot(s-e, vec3(0.299,0.587,0.114)))), max(abs(dot(wv-e, vec3(0.299,0.587,0.114))), abs(dot(ev-e, vec3(0.299,0.587,0.114)))));
                    if (edge > 0.035) col = mix(col, (n+s+wv+ev)*0.25, 0.25);
                }
                float finalLuma = dot(col, vec3(0.2126, 0.7152, 0.0722));
                col = mix(vec3(finalLuma), col, uSaturation);
                outColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
            """;

    // ------------------------------------------------------------------ estado

    private final Config cfg;
    private volatile boolean running = true;
    private final AtomicReference<FrameExchange> exchangeRef = new AtomicReference<>();
    private AiSuperResolution aiProcessor;

    // estado do render (so a thread principal)
    private long window;
    private int fbW, fbH;
    private boolean sharpOn = true;
    private float sharp;
    private float saturationLevel = 1.10f;
    private volatile boolean muted = false;
    private volatile float volume = 1.0f;
    private boolean fullscreen = false;
    private int windowedX = 100, windowedY = 100, windowedW = 1280, windowedH = 720;
    private Thread audioThread;
    private volatile boolean audioRunning = true;

    private SwitchPlayerApp(Config cfg) {
        this.cfg = cfg;
        this.sharp = cfg.sharp();
        this.saturationLevel = cfg.saturation();
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        if (cfg.list()) {
            runFfmpeg("-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
            return;
        }
        if (cfg.modes()) {
            runFfmpeg("-hide_banner", "-list_options", "true", "-f", "dshow", "-i", cfg.dshowInput());
            return;
        }
        if (args.length == 0) {
            cfg = showLauncher();
            if (cfg == null) return;
        }
        new SwitchPlayerApp(cfg).run();
    }

    /** Launcher Swing nativo: configura dispositivos antes de inicializar GLFW. */
    private static Config showLauncher() throws Exception {
        final Config[] result = new Config[1];
        Runnable ui = () -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            JFrame frame = new JFrame("Mvisual — Launcher Alpha");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            try (InputStream in = SwitchPlayerApp.class.getResourceAsStream("/icon.png")) {
                if (in != null) frame.setIconImage(ImageIO.read(in));
            } catch (Exception ignored) { }
            frame.setSize(600, 650);
            frame.setResizable(true);
            frame.setLocationRelativeTo(null);
            JPanel root = new JPanel(new GridBagLayout());
            root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 0);
            JLabel title = new JLabel("Mvisual Alpha v1.0.0"); title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            root.add(title, c);
            c.gridy++; root.add(new JLabel("Selecione a captura e a saída de áudio antes de iniciar."), c);

            JComboBox<String> video = new JComboBox<>(new String[]{"USB Video", "Cam Link 4K", "OBS Virtual Camera", "desktop"});
            video.setEditable(true);
            JComboBox<String> audio = new JComboBox<>(new String[]{"Digital Audio Interface (USB Digital Audio)", "Interface de áudio digital (USB Audio Device)", "Microfone (USB Audio Device)", "(Sem Áudio)"});
            audio.setEditable(true); audio.setSelectedIndex(0);
            List<Mixer.Info> mixers = new ArrayList<>();
            List<String> mixerLabels = new ArrayList<>();
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                boolean supports = false;
                for (javax.sound.sampled.Line.Info lineInfo : mixer.getSourceLineInfo()) {
                    if (lineInfo instanceof DataLine.Info di && SourceDataLine.class.isAssignableFrom(di.getLineClass())) { supports = true; break; }
                }
                if (supports) { mixers.add(info); mixerLabels.add(info.getName()); }
            }
            JComboBox<String> output = new JComboBox<>(mixerLabels.toArray(String[]::new));
            output.setEditable(false);
            java.util.LinkedHashSet<String> resolutionChoices = new java.util.LinkedHashSet<>();
            resolutionChoices.add("Automática (resolução do Windows)");
            try {
                java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                for (java.awt.DisplayMode dm : gd.getDisplayModes()) {
                    if (dm.getWidth() >= 640 && dm.getHeight() >= 480)
                        resolutionChoices.add(dm.getWidth() + "x" + dm.getHeight());
                }
            } catch (Exception ignored) { }
            resolutionChoices.add("1280x720"); resolutionChoices.add("1920x1080"); resolutionChoices.add("2560x1440"); resolutionChoices.add("3840x2160");
            JComboBox<String> resolution = new JComboBox<>(resolutionChoices.toArray(String[]::new));
            JComboBox<String> gpu = new JComboBox<>(new String[]{"Automático (recomendado)", "AMD Radeon", "NVIDIA GeForce / RTX", "Intel / Outra"});
            JComboBox<String> profile = new JComboBox<>(new String[]{"Baixa latência", "Equilibrado", "Qualidade (mais filtros)"});
            addLauncherRow(root, c, "Preferência de GPU", gpu);
            addLauncherRow(root, c, "Perfil gráfico", profile);
            JComboBox<String> rate = new JComboBox<>(new String[]{"60 FPS MJPEG", "60 FPS YUY2", "30 FPS"});
            addLauncherRow(root, c, "Fonte de vídeo (DirectShow/GDI)", video);
            addLauncherRow(root, c, "Entrada de áudio da captura", audio);
            addLauncherRow(root, c, "Saída de áudio do Windows", output);
            addLauncherRow(root, c, "Resolução de saída", resolution);
            JComboBox<String> scaleMode = new JComboBox<>(new String[]{"Preservar proporção", "Preencher (cortar bordas)", "Esticar para preencher"});
            addLauncherRow(root, c, "Escala da imagem", scaleMode);
            JSlider saturation = new JSlider(0, 200, 110);
            saturation.setMajorTickSpacing(50); saturation.setPaintTicks(true); saturation.setPaintLabels(true);
            addLauncherRow(root, c, "Saturação de cor (%)", saturation);
            JCheckBox fxaa = new JCheckBox("Suavização de serrilhado (FXAA - experimental)", false);
            c.gridy++; c.insets = new Insets(4, 0, 4, 0); root.add(fxaa, c);
            JCheckBox ai = new JCheckBox("Super-resolução por IA (requer modelo ONNX em models)", false);
            c.gridy++; c.insets = new Insets(4, 0, 4, 0); root.add(ai, c);
            addLauncherRow(root, c, "Formato / taxa de quadros", rate);
            JButton start = new JButton("Iniciar Mvisual (Alpha)");
            start.setFont(start.getFont().deriveFont(Font.BOLD, 14f));
            c.gridy++; c.insets = new Insets(14, 0, 0, 0); root.add(start, c);
            JLabel note = new JLabel("Dica: para desktop, a captura usa gdigrab. Dispositivos dependem do nome no FFmpeg.");
            note.setFont(note.getFont().deriveFont(10f)); c.gridy++; c.insets = new Insets(9, 0, 0, 0); root.add(note, c);
            start.addActionListener(e -> {
                String v = String.valueOf(video.getEditor().getItem()).trim();
                String a = String.valueOf(audio.getEditor().getItem()).trim();
                String r = String.valueOf(resolution.getSelectedItem());
                int w, h;
                if (r.startsWith("Automática")) {
                    java.awt.Dimension d = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                    w = d.width; h = d.height;
                } else {
                    String[] wh = r.split("x"); w = Integer.parseInt(wh[0]); h = Integer.parseInt(wh[1]);
                }
                System.out.println("[launcher] GPU preferida=" + gpu.getSelectedItem() + " | perfil=" + profile.getSelectedItem());
                System.out.println("[launcher] Resolução de captura/saída solicitada=" + w + "x" + h);
                String fpsChoice = String.valueOf(rate.getSelectedItem());
                int fps = fpsChoice.startsWith("30") ? 30 : 60;
                boolean mjpeg = fpsChoice.contains("MJPEG");
                String mixerName = output.getSelectedItem() == null ? "" : output.getSelectedItem().toString();
                result[0] = new Config(v, a, mixerName, w, h, fps, mjpeg, 0.6f, 1.0f, saturation.getValue() / 100.0f, fxaa.isSelected(), String.valueOf(scaleMode.getSelectedItem()), ai.isSelected(), String.valueOf(gpu.getSelectedItem()), false, false, false);
                frame.dispose();
            });
            frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { synchronized (result) { result.notifyAll(); } } });
            frame.setContentPane(root); frame.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeAndWait(ui);
        // JFrame dispose exits this method through a short wait; no polling/timer thread is needed.
        while (result[0] == null && java.awt.Frame.getFrames().length > 0) {
            boolean visible = false;
            for (java.awt.Frame f : java.awt.Frame.getFrames()) if (f.isVisible() && f.getTitle().startsWith("Mvisual — Launcher")) visible = true;
            if (!visible) break;
            Thread.sleep(100);
        }
        return result[0];
    }

    private static void addLauncherRow(JPanel root, GridBagConstraints c, String label, JComponent input) {
        c.gridy++; c.insets = new Insets(5, 0, 0, 0); root.add(new JLabel(label), c);
        c.gridy++; c.insets = new Insets(2, 0, 4, 0); root.add(input, c);
    }

    /** Usa o ffmpeg.exe empacotado no JavaCV para listar dispositivos/modos dshow. */
    private static void runFfmpeg(String... args) throws Exception {
        String exe = Loader.load(ffmpeg.class);
        String[] cmd = new String[args.length + 1];
        cmd[0] = exe;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        p.waitFor();
    }

    /**
     * O .exe e um launcher GUI (sem console): stdout/stderr some. Quando o jpackage passa
     * -Dmvisual.log=file, a saida vai para %LOCALAPPDATA%\\Mvisual\\mvisual.log.
     */
    private static void redirectLogToFile() {
        if (!"file".equals(System.getProperty("mvisual.log"))) return;
        try {
            String base = System.getenv("LOCALAPPDATA");
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    base != null ? base : System.getProperty("user.home"), "Mvisual");
            java.nio.file.Files.createDirectories(dir);
            java.io.PrintStream ps = new java.io.PrintStream(
                    new java.io.FileOutputStream(dir.resolve("mvisual.log").toFile(), false), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception ignored) {
            // sem log em arquivo: segue com a saida padrao
        }
    }

    private void run() {
        redirectLogToFile();
        Thread capture = new Thread(this::captureLoop, "capture");
        capture.setDaemon(true);
        capture.setPriority(Thread.MAX_PRIORITY);
        capture.start();

        try {
            renderLoop();   // thread principal = thread GLFW/OpenGL
        } finally {
            running = false;
            audioRunning = false;
            if (audioThread != null) {
                audioThread.interrupt();
                try { audioThread.join(1200); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            try {
                capture.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------- THREAD 1: captura

    private FFmpegFrameGrabber openGrabber(boolean withAudio) {
        boolean desktop = cfg.device().equalsIgnoreCase("desktop");
        String input;
        if (desktop) input = "desktop";
        else {
            String video = cfg.device().startsWith("video=") ? cfg.device().substring(6) : cfg.device();
            boolean audioEnabled = withAudio && cfg.audioDevice() != null && !cfg.audioDevice().isBlank() && !cfg.audioDevice().equals("(Sem Áudio)");
            input = "video=" + video;
            if (audioEnabled) {
                String audio = cfg.audioDevice().startsWith("audio=") ? cfg.audioDevice().substring(6) : cfg.audioDevice();
                input += ":audio=" + audio;
            }
        }
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(input);
        g.setFormat(desktop ? "gdigrab" : "dshow");
        g.setImageWidth(cfg.width()); g.setImageHeight(cfg.height());
        g.setFrameRate(cfg.fps());
        if (desktop) {
            g.setOption("framerate", Integer.toString(cfg.fps()));
            g.setOption("draw_mouse", "1");
        } else if (cfg.mjpeg()) {
            g.setOption("vcodec", "mjpeg");
        }
        g.setOption("fflags", "nobuffer");
        g.setOption("flags", "low_delay");
        g.setOption("audio_buffer_size", "20");
        g.setOption("probesize", "32");
        g.setOption("analyzeduration", "0");
        g.setOption("rtbufsize", "64M");
        return g;
    }

    private SourceDataLine openAudioLine(FFmpegFrameGrabber grabber) throws Exception {
        int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48000;
        int channels = grabber.getAudioChannels() > 0 ? Math.min(2, grabber.getAudioChannels()) : 2;
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        Mixer.Info chosen = null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) if (mi.getName().equals(cfg.outputMixer())) { chosen = mi; break; }
        SourceDataLine line;
        if (chosen != null) {
            Mixer mixer = AudioSystem.getMixer(chosen);
            line = (SourceDataLine) mixer.getLine(info);
        } else line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, Math.max(4096, sampleRate * channels / 5));
        line.start();
        return line;
    }

    private void playAudioFrame(Frame frame, SourceDataLine line) {
        if (frame.samples == null || line == null || muted) return;
        for (Buffer sample : frame.samples) {
            if (sample instanceof ShortBuffer shorts) {
                ShortBuffer copy = shorts.duplicate();
                byte[] bytes = new byte[copy.remaining() * 2];
                int i = 0;
                while (copy.hasRemaining()) {
                    int value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(copy.get() * volume)));
                    bytes[i++] = (byte) (value & 0xff); bytes[i++] = (byte) ((value >>> 8) & 0xff);
                }
                line.write(bytes, 0, bytes.length);
            } else if (sample instanceof ByteBuffer bb) {
                ByteBuffer copy = bb.duplicate(); byte[] bytes = new byte[copy.remaining()]; copy.get(bytes);
                if (volume < 0.999f) for (int i = 0; i + 1 < bytes.length; i += 2) {
                    short v = (short) ((bytes[i + 1] << 8) | (bytes[i] & 0xff));
                    int scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * volume)));
                    bytes[i] = (byte) scaled; bytes[i + 1] = (byte) (scaled >>> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
        }
    }

    private void captureLoop() {
        long frames = 0, t0 = System.nanoTime();
        while (running) {
            FFmpegFrameGrabber g = null;
            SourceDataLine line = null;
            boolean wantAudio = cfg.audioDevice() != null && !cfg.audioDevice().isBlank() && !cfg.audioDevice().equals("(Sem Áudio)") && !cfg.device().equalsIgnoreCase("desktop");
            try {
                try {
                    g = openGrabber(wantAudio);
                    g.start();
                    if (wantAudio && g.getAudioChannels() > 0) {
                        try { line = openAudioLine(g); }
                        catch (Exception audioOpenError) { System.err.println("[audio] saída indisponível: " + audioOpenError.getMessage()); }
                    } else if (wantAudio) {
                        throw new IllegalStateException("A entrada combinada abriu sem canal de áudio");
                    }
                } catch (Exception combinedError) {
                    if (!wantAudio) throw combinedError;
                    System.err.println("[capture] entrada combinada falhou; tentando somente vídeo: " + combinedError.getMessage());
                    if (g != null) try { g.stop(); } catch (Exception ignored) { }
                    if (g != null) try { g.release(); } catch (Exception ignored) { }
                    g = openGrabber(false);
                    g.start();
                }
                System.out.println("[capture] aberto: " + g.getImageWidth() + "x" + g.getImageHeight() + " @ " + g.getFrameRate() + " fps");
                if (cfg.aiEnabled() && aiProcessor == null) {
                    try { aiProcessor = AiSuperResolution.open(java.nio.file.Paths.get("models", "realesrgan-x2plus.onnx"), cfg.gpuBackend());
                          System.out.println("[AI] Super-resolução ONNX ativa: " + aiProcessor.backendName()); }
                    catch (Exception aiError) { System.err.println("[AI] não foi possível iniciar: " + aiError.getMessage()); }
                }
                FrameExchange ex = null;
                while (running) {
                    Frame f = g.grab();
                    if (f == null) break;
                    if (f.samples != null) playAudioFrame(f, line);
                    if (f.image == null || f.image.length == 0) continue;
                    ByteBuffer src = (ByteBuffer) f.image[0];
                    ByteBuffer output = null;
                    int outW = f.imageWidth, outH = f.imageHeight;
                    if (aiProcessor != null) {
                        try {
                            AiSuperResolution.ProcessedFrame aiFrame = aiProcessor.process(src, f.imageWidth, f.imageHeight, f.imageStride);
                            output = aiFrame.bgr(); outW = aiFrame.width(); outH = aiFrame.height();
                        } catch (Exception aiError) {
                            System.err.println("[AI] quadro ignorado; IA desativada: " + aiError.getMessage());
                            try { aiProcessor.close(); } catch (Exception ignored) {}
                            aiProcessor = null;
                        }
                    }
                    if (ex == null || ex.width != outW || ex.height != outH) {
                        ex = new FrameExchange(outW, outH);
                        exchangeRef.set(ex);
                    }
                    if (output != null) {
                        ByteBuffer dst = ex.writeBuffer(); output.rewind(); dst.put(output); dst.flip();
                    } else {
                        long srcAddress = MemoryUtil.memAddress(src);
                        long dstAddress = MemoryUtil.memAddress(ex.writeBuffer());
                        int row = f.imageWidth * 3, stride = f.imageStride;
                        if (stride == row && outW == f.imageWidth) MemoryUtil.memCopy(srcAddress, dstAddress, ex.bytes);
                        else if (outW == f.imageWidth && outH == f.imageHeight) for (int y = 0; y < outH; y++) MemoryUtil.memCopy(srcAddress + (long)y * stride, dstAddress + (long)y * row, row);
                        else continue;
                    }
                    ex.publish();
                    if (cfg.debug() && ++frames % 120 == 0) {
                        double sec = (System.nanoTime() - t0) / 1e9;
                        System.out.printf("[capture] %.1f fps%n", 120 / sec); t0 = System.nanoTime();
                    }
                }
            } catch (Exception e) {
                System.err.println("[capture] erro: " + e.getMessage());
            } finally {
                if (line != null) { try { line.stop(); } catch (Exception ignored) {} try { line.close(); } catch (Exception ignored) {} }
                if (g != null) { try { g.stop(); } catch (Exception ignored) {} try { g.release(); } catch (Exception ignored) {} }
            }
            if (running) try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void toggleFullscreen(long w) {
        if (!fullscreen) {
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer x = st.mallocInt(1), y = st.mallocInt(1), width = st.mallocInt(1), height = st.mallocInt(1);
                glfwGetWindowPos(w, x, y); glfwGetWindowSize(w, width, height);
                windowedX = x.get(0); windowedY = y.get(0); windowedW = width.get(0); windowedH = height.get(0);
            }
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer x = st.mallocInt(1), y = st.mallocInt(1);
                glfwGetMonitorPos(monitor, x, y);
                glfwSetWindowAttrib(w, GLFW_DECORATED, GLFW_FALSE);
                glfwSetWindowPos(w, x.get(0), y.get(0));
                glfwSetWindowSize(w, mode.width(), mode.height());
            }
            fullscreen = true;
        } else {
            glfwSetWindowAttrib(w, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowPos(w, windowedX, windowedY);
            glfwSetWindowSize(w, windowedW, windowedH);
            fullscreen = false;
        }
    }

    private void applyWindowIcon() {
        try (InputStream in = SwitchPlayerApp.class.getResourceAsStream("/icon.png")) {
            if (in == null) return;
            BufferedImage image = ImageIO.read(in);
            int width = image.getWidth(), height = image.getHeight();
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte)((argb >> 16) & 255)).put((byte)((argb >> 8) & 255))
                          .put((byte)(argb & 255)).put((byte)((argb >> 24) & 255));
                }
                pixels.flip();
                try (MemoryStack st = MemoryStack.stackPush()) {
                    GLFWImage.Buffer icons = GLFWImage.mallocStack(1, st);
                    icons.position(0).width(width).height(height).pixels(pixels);
                    glfwSetWindowIcon(window, icons);
                }
            } finally { MemoryUtil.memFree(pixels); }
        } catch (Exception e) { System.err.println("[icon] " + e.getMessage()); }
    }

    // --------------------------------------------------- THREAD 2: render (main)

    private void renderLoop() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Falha ao iniciar GLFW");

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vm = glfwGetVideoMode(monitor);
        if (vm == null) throw new IllegalStateException("Sem video mode do monitor");

        int mx, my;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer x = st.mallocInt(1), y = st.mallocInt(1);
            glfwGetMonitorPos(monitor, x, y);
            mx = x.get(0);
            my = y.get(0);
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_AUTO_ICONIFY, GLFW_FALSE);
        glfwWindowHint(GLFW_RED_BITS, vm.redBits());
        glfwWindowHint(GLFW_GREEN_BITS, vm.greenBits());
        glfwWindowHint(GLFW_BLUE_BITS, vm.blueBits());
        glfwWindowHint(GLFW_REFRESH_RATE, vm.refreshRate());

        // Janela do tamanho exato do monitor, sem bordas = borderless fullscreen
        window = glfwCreateWindow(windowedW, windowedH, "Mvisual", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Falha ao criar janela (OpenGL 3.3 indisponivel?)");
        glfwSetWindowPos(window, mx, my);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);

        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (action == GLFW_RELEASE) return;
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(w, true);
                case GLFW_KEY_F11 -> { if (action == GLFW_PRESS) toggleFullscreen(w); }
                case GLFW_KEY_M -> { if (action == GLFW_PRESS) muted = !muted; }
                case GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD -> { if (action == GLFW_PRESS || action == GLFW_REPEAT) volume = Math.min(2.0f, volume + 0.05f); }
                case GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT -> { if (action == GLFW_PRESS || action == GLFW_REPEAT) volume = Math.max(0.0f, volume - 0.05f); }
                case GLFW_KEY_S -> sharpOn = !sharpOn;
                case GLFW_KEY_UP -> sharp = Math.min(1f, sharp + 0.05f);
                case GLFW_KEY_DOWN -> sharp = Math.max(0f, sharp - 0.05f);
                case GLFW_KEY_RIGHT_BRACKET -> saturationLevel = Math.min(2.0f, saturationLevel + 0.05f);
                case GLFW_KEY_LEFT_BRACKET -> saturationLevel = Math.max(0.0f, saturationLevel - 0.05f);
                default -> { }
            }
        });
        glfwSetFramebufferSizeCallback(window, (w, nw, nh) -> {
            fbW = nw;
            fbH = nh;
        });

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1);   // vsync = refresh nativo do monitor
        applyWindowIcon();
        glfwShowWindow(window);
        glfwFocusWindow(window);

        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer w = st.mallocInt(1), h = st.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            fbW = w.get(0);
            fbH = h.get(0);
        }

        System.out.println("[render] monitor " + vm.width() + "x" + vm.height() + " @ " + vm.refreshRate() + " Hz | GL "
                + glGetString(GL_VERSION));

        int program = buildProgram(VERT, FRAG);
        int uTex = glGetUniformLocation(program, "uTex");
        int uTexel = glGetUniformLocation(program, "uTexel");
        int uSharp = glGetUniformLocation(program, "uSharp");
        int uRadius = glGetUniformLocation(program, "uRadius");
        int uSaturation = glGetUniformLocation(program, "uSaturation");
        int uFxaa = glGetUniformLocation(program, "uFxaa");
        int uUvScale = glGetUniformLocation(program, "uUvScale");

        int vao = glGenVertexArrays();   // VAO vazio (core profile exige um)
        glBindVertexArray(vao);

        int tex = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glUseProgram(program);
        glUniform1i(uTex, 0);
        glUniform1f(uRadius, cfg.radius());
        glUniform1f(uSaturation, cfg.saturation());
        glUniform1i(uFxaa, cfg.fxaa() ? 1 : 0);
        glClearColor(0f, 0f, 0f, 1f);

        FrameExchange ex = null;
        long uploads = 0, t0 = System.nanoTime();

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            if (ex == null) {
                ex = exchangeRef.get();
                if (ex != null) {
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, ex.width, ex.height, 0, GL_BGR, GL_UNSIGNED_BYTE, 0L);
                    glUniform2f(uTexel, 1f / ex.width, 1f / ex.height);
                }
            }

            glClear(GL_COLOR_BUFFER_BIT);

            if (ex != null) {
                ByteBuffer latest = ex.acquireLatest();   // pega o quadro MAIS RECENTE, descarta os velhos
                if (latest != null) {
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ex.width, ex.height,
                            GL_BGR, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(latest));
                    uploads++;
                }

                // Escala configurável: proporção preservada, crop preenchendo ou esticar.
                int vw = fbW, vh = fbH;
                if ("Preservar proporção".equals(cfg.scaleMode())) {
                    if ((long) fbW * ex.height > (long) fbH * ex.width) {
                        vw = (int) ((long) fbH * ex.width / ex.height);
                    } else {
                        vh = (int) ((long) fbW * ex.height / ex.width);
                    }
                } else if ("Preencher (cortar bordas)".equals(cfg.scaleMode())) {
                    // Crop central feito via coordenadas UV no shader seria ideal; aqui usa viewport
                    // preenchido e mantém a textura sem distorção via shader UV ajustado numa próxima etapa.
                    vw = fbW; vh = fbH;
                }
                glViewport((fbW - vw) / 2, (fbH - vh) / 2, vw, vh);
                float uvX = 1.0f, uvY = 1.0f;
                if ("Preencher (cortar bordas)".equals(cfg.scaleMode())) {
                    float sourceAspect = (float) ex.width / ex.height;
                    float targetAspect = (float) fbW / fbH;
                    if (targetAspect > sourceAspect) uvY = sourceAspect / targetAspect;
                    else uvX = targetAspect / sourceAspect;
                }
                glUniform2f(uUvScale, uvX, uvY);
                glUniform1f(uSharp, sharpOn ? sharp : 0f);
                glUniform1f(uSaturation, saturationLevel);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }

            glfwSwapBuffers(window);

            if (cfg.debug() && System.nanoTime() - t0 > 2_000_000_000L) {
                double sec = (System.nanoTime() - t0) / 1e9;
                System.out.printf("[render] %.1f uploads/s%n", uploads / sec);
                uploads = 0;
                t0 = System.nanoTime();
            }
        }

        // limpeza
        running = false;
        glDeleteTextures(tex);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        glfwDestroyWindow(window);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
        // Os buffers nativos sao liberados pelo SO ao fim do processo (a thread de captura
        // ainda pode estar escrevendo neles; evitar free concorrente).
    }

    // ------------------------------------------------------------- helpers GL

    private static int buildProgram(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Link falhou: " + glGetProgramInfoLog(p));
        }
        glDeleteShader(v);
        glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader falhou: " + glGetShaderInfoLog(s));
        }
        return s;
    }
}
