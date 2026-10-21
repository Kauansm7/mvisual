<div align="center">
  <img src="src/main/resources/icon.png" alt="Logo Mvisual" width="160">
  <h1>Mvisual (SwitchPlayer)</h1>
  <p><strong>Visualização de captura de vídeo em baixa latência para Windows</strong></p>
  <p>Java 17 · LWJGL 3 · GLFW · OpenGL 3.3 · JavaCV/FFmpeg</p>
</div>

## Sobre

Mvisual é um visualizador de vídeo pensado para placas de captura compatíveis com DirectShow. O pipeline usa captura FFmpeg, buffers off-heap e renderização OpenGL com shader de nitidez adaptativa. A janela preserva a proporção do sinal e pode alternar entre janela e tela cheia sem bordas.

> **Estado do áudio:** a captura de áudio é opcional e deve ser configurada com `--audio-device` usando o nome exato do dispositivo DirectShow. A disponibilidade e o formato de amostras dependem do dispositivo/driver. A opção de volume atua no fluxo PCM de 16 bits.

## Recursos

- Captura de vídeo via DirectShow (FFmpeg).
- Renderização GPU OpenGL 3.3 e nitidez adaptativa via fragment shader.
- Letterbox/pillarbox automático para preservar a proporção da imagem.
- Alternância janela/tela cheia sem bordas.
- Captura de áudio opcional e controle de volume/mudo.
- Imagem de aplicação Windows com runtime Java incluído via `jpackage`.

## Atalhos

| Tecla | Ação |
|---|---|
| `F11` | Alterna janela e tela cheia sem bordas |
| `M` | Mudo / desmudo (quando áudio configurado) |
| `+` ou `=` | Aumenta o volume |
| `-` | Diminui o volume |
| `S` | Liga/desliga a nitidez |
| `↑` / `↓` | Aumenta/diminui a nitidez |
| `Esc` | Fecha a aplicação |

## Requisitos para compilar

- Windows 10/11 x64.
- JDK 17 (com `jpackage`, incluído no JDK).
- Maven 3.9 ou superior.
- Para gerar o instalador: Inno Setup 6.
- Placa de captura e drivers DirectShow compatíveis para uso real.

## Compilação

Abra um terminal na pasta do projeto e execute:

```bat
mvn clean package
```

No Windows, o perfil Maven `windows-exe` é ativado automaticamente e gera a imagem da aplicação em `target/dist/Mvisual`, incluindo runtime Java. Também é possível executar `build.bat`: ele compila e, se o Inno Setup 6 estiver instalado, gera o instalador automaticamente.

Para criar o instalador depois da compilação:

```bat
ISCC.exe installer.iss
```

O instalador será salvo em `target/installer/Mvisual-Setup-v1.0.0.exe`.

## Instalação e uso

1. Baixe `Mvisual-Setup-v1.0.0.exe` na página de Releases.
2. Execute o instalador e siga o assistente.
3. Abra Mvisual pelo Menu Iniciar ou pelo atalho da Área de Trabalho, se selecionado.
4. Por padrão, o dispositivo de vídeo é `USB Video`, resolução 1920×1080 e 60 FPS. Ajuste os argumentos de inicialização se seu dispositivo usar outro nome.

Exemplos de argumentos:

```bat
Mvisual.exe --device "USB Video" --size 1920x1080 --fps 60
Mvisual.exe --device "USB Video" --audio-device "Microphone (USB Audio)" --size 1920x1080 --fps 60
Mvisual.exe --list
Mvisual.exe --device "USB Video" --modes
```

`--list` lista dispositivos de vídeo DirectShow. `--modes` lista os modos do dispositivo especificado por `--device`. Para áudio, use o nome exato do dispositivo de áudio no argumento `--audio-device`.

## Build automático (GitHub Actions)

O workflow `.github/workflows/release.yml` compila no Windows, gera a imagem com `jpackage`, cria o instalador com Inno Setup e anexa o executável à Release. Para publicar uma versão, crie e envie uma tag, por exemplo:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Também é possível executar o workflow manualmente pela aba **Actions**. Builds manuais geram um artefato baixável; tags `v*` publicam uma GitHub Release.

## Estrutura

```text
Mvisual/
├── .github/workflows/release.yml
├── src/main/java/com/switchplayer/SwitchPlayerApp.java
├── src/main/resources/
│   ├── icon.ico
│   ├── icon.png
│   └── Mvisual.svg
├── installer.iss
├── pom.xml
├── build.bat
└── README.md
```

## Observações de distribuição

- A imagem `target/dist/Mvisual` é específica para Windows x64, conforme os classifiers de dependências do projeto.
- O instalador distribui o runtime gerado por `jpackage`; o usuário final não precisa instalar Java separadamente.
- Revise as licenças e avisos de JavaCV/FFmpeg, LWJGL e demais dependências antes de publicar uma distribuição open source.

## Testes manuais da Alpha

1. Instale JDK 17 e Maven 3.9+; conecte a placa de captura antes de abrir o programa.
2. Execute `build.bat` para compilar e, se o Inno Setup 6 estiver instalado, gerar o instalador.
3. Execute `mvn exec:java` se a configuração Maven local tiver o plugin exec, ou execute a classe principal pelo IDE; o launcher abre antes da janela do monitor.
4. No launcher, selecione o dispositivo de vídeo, a entrada de áudio, a saída de áudio, a resolução automática ou uma resolução listada no Windows, a preferência de GPU e o perfil gráfico.
5. Inicie a captura e confira imagem e áudio antes de alternar para tela cheia.

### Teclas durante a captura

- `F11`: alternar entre janela e tela cheia sem bordas.
- `S`: ligar/desligar a nitidez adaptativa GLSL.
- `Seta para cima` / `Seta para baixo`: aumentar/diminuir a nitidez.
- `M`: silenciar/reativar áudio.
- `+` ou `=` / `-`: aumentar/diminuir volume.
- `Esc`: fechar o monitor.

### Limites desta Alpha

A escolha AMD/NVIDIA no launcher é uma preferência informativa; OpenGL usa o driver/GPU selecionado pelo Windows e não força por si só uma placa específica. O perfil de qualidade não garante uma porcentagem de uso de GPU. Esta versão tem nitidez GLSL e escalonamento OpenGL, mas não inclui ainda um modelo de super-resolução por IA nem interpolação de quadros. A resolução automática usa o tamanho do monitor principal do Windows como valor inicial, e não a lista de modos suportados pela placa de captura.


## Controles de imagem adicionais (build de trabalho)
- Launcher: saturação de 0 a 200%, suavização experimental de bordas e modo de escala.
- Escala: preservar proporção; preencher com corte central (sem barras pretas); esticar para preencher.
- Durante a exibição: `[` diminui saturação e `]` aumenta saturação.
- A suavização implementada é um filtro espacial leve, não IA e não substitui reconstrução neural.
- Aumentar uma captura 1080p para 4K não recupera automaticamente detalhes que não chegaram pela placa.


## Super-resolução neural experimental (ONNX)

O launcher tem a opção **Super-resolução por IA**. Para ativá-la, coloque um modelo ONNX compatível em:

```text
models/realesrgan-x2plus.onnx
```

O modelo precisa aceitar entrada RGB float `NCHW` (`1x3xHxW`) e produzir saída RGB float `NCHW`, preferencialmente escala 2x para ampliar 1080p a 4K. Modelos exportados com entradas/saídas dinâmicas são recomendados. O código tenta CUDA quando NVIDIA é escolhida e DirectML quando AMD/Automático é escolhido; se o provider não estiver disponível no runtime nativo instalado, usa CPU e escreve o backend no log. **O arquivo de pesos/modelo não está incluído neste ZIP**: é um artefato grande, separado, e precisa ser compatível com esta interface de tensor. A opção fica desligada por padrão.

**Desempenho:** a inferência neural de um quadro inteiro pode demorar mais que o orçamento de 16,7 ms de 60 FPS. Use a opção somente depois de colocar um modelo adequado; se a inferência não acompanhar a captura, o programa desativa a IA após erro em vez de travar indefinidamente. Próxima otimização recomendada: inferência por tiles com sobreposição e compartilhamento de textura GPU para evitar cópias CPU/GPU.

### Argumentos de execução

- `--ai`: ativa super-resolução neural
- `--gpu "AMD Radeon"` ou `--gpu "NVIDIA GeForce / RTX"`: preferência de provider
- `--debug`: mostra contagem de quadros

O suporte CUDA/DirectML depende dos binários nativos incluídos na distribuição ONNX Runtime. O artefato Maven padrão não garante que ambos os providers estejam presentes; o log indica se houve fallback para CPU.
