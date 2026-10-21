package com.switchplayer;

import ai.onnxruntime.*;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Optional ONNX super-resolution backend. Expects an RGB NCHW model whose output is
 * RGB NCHW (e.g. a compatible Real-ESRGAN x2 ONNX export). Model files are user-provided.
 * DirectML/CUDA are attempted according to the launcher's preference; CPU is the fallback.
 */
public final class AiSuperResolution implements AutoCloseable {
    public record ProcessedFrame(int width, int height, ByteBuffer bgr) {}

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String backendName;
    private final int scaleX, scaleY;

    private AiSuperResolution(Path model, String gpuPreference) throws Exception {
        if (!Files.isRegularFile(model)) throw new IllegalArgumentException("Modelo ONNX não encontrado: " + model.toAbsolutePath());
        env = OrtEnvironment.getEnvironment();
        OrtSession made = null;
        String used = "CPU";
        Exception last = null;
        String pref = gpuPreference == null ? "" : gpuPreference.toLowerCase();
        // Provider-specific EPs are optional in ONNX Runtime Java builds; fall back safely.
        if (pref.contains("nvidia") || pref.contains("automático") || pref.contains("automatic")) {
            try (OrtSession.SessionOptions opts = options()) {
                opts.addCUDA(0);
                made = env.createSession(model.toString(), opts);
                used = "NVIDIA CUDA";
            } catch (Throwable ex) { last = new Exception("CUDA indisponível: " + ex.getMessage(), ex); }
        }
        if (made == null && (pref.contains("amd") || pref.contains("automático") || pref.contains("automatic") || pref.contains("intel") || pref.isBlank())) {
            try (OrtSession.SessionOptions opts = options()) {
                opts.addDirectML(0);
                made = env.createSession(model.toString(), opts);
                used = "DirectML (GPU compatível)";
            } catch (Throwable ex) { last = new Exception("DirectML indisponível nesta distribuição do ONNX Runtime: " + ex.getMessage(), ex); }
        }
        if (made == null) {
            try (OrtSession.SessionOptions opts = options()) {
                made = env.createSession(model.toString(), opts);
                used = "CPU (fallback)";
            } catch (Exception ex) {
                if (last != null) ex.addSuppressed(last);
                throw ex;
            }
        }
        session = made;
        backendName = used;
        inputName = session.getInputNames().iterator().next();
        long[] inShape = session.getInputInfo().get(inputName).getInfo() instanceof TensorInfo ti ? ti.getShape() : null;
        long[] outShape = session.getOutputInfo().values().iterator().next().getInfo() instanceof TensorInfo ti ? ti.getShape() : null;
        if (inShape == null || outShape == null || inShape.length != 4 || outShape.length != 4)
            throw new IllegalArgumentException("O modelo precisa aceitar e retornar tensor NCHW de 4 dimensões.");
        if (inShape[1] > 0 && inShape[1] != 3 || outShape[1] > 0 && outShape[1] != 3)
            throw new IllegalArgumentException("O modelo precisa usar 3 canais RGB.");
        if (inShape[2] > 0 && outShape[2] > 0) scaleY = Math.toIntExact(outShape[2] / inShape[2]); else scaleY = 2;
        if (inShape[3] > 0 && outShape[3] > 0) scaleX = Math.toIntExact(outShape[3] / inShape[3]); else scaleX = 2;
        if (scaleX < 1 || scaleY < 1 || scaleX > 4 || scaleY > 4)
            throw new IllegalArgumentException("Escala do modelo não suportada: " + scaleX + "x" + scaleY);
    }

    private static OrtSession.SessionOptions options() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        return opts;
    }

    public static AiSuperResolution open(Path model, String gpuPreference) throws Exception {
        return new AiSuperResolution(model, gpuPreference);
    }
    public String backendName() { return backendName; }

    public ProcessedFrame process(ByteBuffer bgr, int width, int height, int stride) throws Exception {
        // NCHW float RGB 0..1. Keep the source stride from FFmpeg; no Swing image conversions.
        float[] tensor = new float[3 * width * height];
        ByteBuffer src = bgr.duplicate();
        int plane = width * height;
        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int x = 0; x < width; x++) {
                int p = row + x * 3;
                int b = src.get(p) & 255, g = src.get(p + 1) & 255, r = src.get(p + 2) & 255;
                int i = y * width + x;
                tensor[i] = r / 255f;
                tensor[plane + i] = g / 255f;
                tensor[2 * plane + i] = b / 255f;
            }
        }
        long[] shape = new long[]{1, 3, height, width};
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            Object value = result.get(0).getValue();
            if (!(value instanceof float[][][][] out) || out.length == 0 || out[0].length < 3)
                throw new IllegalStateException("Saída ONNX inesperada. Use um modelo float NCHW RGB.");
            int oh = out[0][0].length, ow = out[0][0][0].length;
            if (ow < width || oh < height || ow > width * 4 || oh > height * 4)
                throw new IllegalStateException("Dimensões de saída ONNX não suportadas: " + ow + "x" + oh);
            ByteBuffer packed = ByteBuffer.allocateDirect(ow * oh * 3).order(ByteOrder.nativeOrder());
            for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
                int i = Math.min(255, Math.max(0, Math.round(out[0][2][y][x] * 255f))); packed.put((byte)i);
                i = Math.min(255, Math.max(0, Math.round(out[0][1][y][x] * 255f))); packed.put((byte)i);
                i = Math.min(255, Math.max(0, Math.round(out[0][0][y][x] * 255f))); packed.put((byte)i);
            }
            packed.flip();
            return new ProcessedFrame(ow, oh, packed);
        }
    }

    @Override public void close() throws Exception { session.close(); }
}
