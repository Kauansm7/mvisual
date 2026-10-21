# Mvisual Alpha --- Documentação de continuidade do projeto

**Data de referência:** 20/10/2026 — atualização de continuidade após o teste em Debug\
**Objetivo deste documento:** registrar o estado conhecido do Mvisual,
comandos de build/teste, teclas, limitações já identificadas e um prompt
completo para retomar o trabalho na próxima sessão.

> **Regra de trabalho:** o líder do projeto decide quando e como
> validar. Não bloquear a entrega esperando validação prévia. Seja
> transparente sobre o que está implementado, o que foi apenas planejado
> e o que ainda não foi testado.

------------------------------------------------------------------------

## 1. Visão geral

Mvisual (também chamado SwitchPlayer no código) é um visualizador de
captura de vídeo para Windows, pensado para funcionar como uma
TV/monitor virtual para consoles ou computadores ligados a uma placa de
captura HDMI/UVC genérica. A ideia é abrir o vídeo capturado em uma
janela própria, com modo janela e tela cheia, controle de áudio e
processamento gráfico acelerado pela GPU.

Tecnologias identificadas no projeto: - Java 17 - Swing para o
launcher - LWJGL 3, GLFW e OpenGL 3.3 para a janela de exibição -
JavaCV/FFmpeg para captura DirectShow - `SourceDataLine` / Java Sound
para saída de áudio - Maven para compilação - `jpackage` para empacotar
runtime/aplicação Windows - Inno Setup 6 para criar instalador Windows

**GPU do líder:** AMD Radeon RX 9060 XT. O programa deve continuar sendo
desenhado para AMD e NVIDIA, além de não impedir o uso de outras GPUs
compatíveis com OpenGL.

## 2. O que está presente no código-fonte inspecionado

O ZIP-base inspecionado contém: -
`src/main/java/com/switchplayer/SwitchPlayerApp.java` -
`src/main/resources/icon.ico`, `icon.png` e `Mvisual.svg` - `pom.xml` -
`build.bat` - `installer.iss` - `.github/workflows/release.yml` -
`README.md`

### Recursos identificados no código

-   Launcher Swing intitulado "Mvisual --- Launcher Alpha".
-   Seletores editáveis para fonte de vídeo e entrada de áudio.
-   Seleção da saída de áudio a partir dos mixers que oferecem
    `SourceDataLine`.
-   Opções fixas de resolução `1920x1080`, `1280x720` e `3840x2160`.
-   Opções de captura `60 FPS MJPEG`, `60 FPS YUY2` e `30 FPS`.
-   Captura de vídeo DirectShow usando JavaCV/FFmpeg.
-   Captura de áudio opcional via `--audio-device`; saída PCM por Java
    Sound.
-   Renderização OpenGL 3.3.
-   Shader GLSL de nitidez adaptativa, descrito no código como inspirado
    no estilo AMD FidelityFX CAS. Isso **não** é o FidelityFX CAS
    oficial e **não** é IA.
-   Buffer de troca de quadros para entregar o quadro mais recente e
    descartar quadros atrasados.
-   VSync ativado via `glfwSwapInterval(1)`.
-   Proporção da imagem preservada por letterbox/pillarbox na área de
    exibição.
-   Alternância de janela e tela cheia sem bordas com F11.
-   Controles de volume e mute.
-   Opções de linha de comando para listar dispositivos e modos.
-   Scripts para build e empacotamento.

### Estado que NÃO deve ser presumido

A partir do código inspecionado, não se deve afirmar que já existem: -
Inferência de IA real para super-resolução, restauração ou denoise. -
FSR 2/3/4, DLSS, XeSS ou frame generation integrados. - Detecção
automática de fabricante de GPU e ativação real de backends
AMD/NVIDIA. - Seleção "Automática" de resolução do Windows ou listagem
dinâmica de todos os modos do monitor no launcher. - Controle de uso de
GPU com meta garantida de 50%. - Modo de preenchimento sem barras pretas
com corte (crop) configurável. - Testes completos de captura/áudio em
hardware real nesta sessão.

O launcher do código inspecionado mostra três resoluções fixas. Se um
pacote mais recente adicionou controles extras, revisar o código desse
pacote antes de atualizar este estado.

## 3. Teclas durante a exibição

  Tecla        Comportamento no código
  ------------ ----------------------------------------------------
  `F11`        Alterna janela e tela cheia sem bordas
  `S`          Liga/desliga a nitidez
  `↑`          Aumenta a intensidade da nitidez em passos de 0,05
  `↓`          Diminui a intensidade da nitidez em passos de 0,05
  `M`          Liga/desliga o mute
  `+` ou `=`   Aumenta o volume em passos de 5%, até 200%
  `-`          Diminui o volume em passos de 5%, até 0%
  `Esc`        Fecha a janela/aplicação

Observações: - O áudio só funciona quando a captura de áudio foi
configurada e o dispositivo fornece formato compatível. - `F11` não
equivale a trocar a resolução física do monitor; altera o modo da
janela. - O modo de proporção preservada pode exibir barras pretas se a
proporção da fonte for diferente da janela.

## 4. Comandos para Windows

Execute os comandos em um terminal na pasta `Mvisual` extraída do ZIP.

### Conferir ferramentas

``` bat
java -version
mvn -version
where java
where mvn
```

Requisitos recomendados: - Windows 10/11 x64 - JDK 17 (inclui
`jpackage`) - Maven 3.9+ - Inno Setup 6, para o instalador - Drivers da
placa de captura e dispositivo DirectShow

### Build completo

``` bat
mvn clean package
```

Ou use o script:

``` bat
build.bat
```

O `build.bat`: 1. Confere se `java` e `mvn` estão no PATH. 2. Avisa se a
versão do Java não parece ser 17. 3. Executa `mvn clean package`. 4. Se
encontrar Inno Setup 6, executa `installer.iss`. 5. Mostra o local
esperado dos artefatos.

Locais descritos pelo README: - Aplicação empacotada:
`target\dist\Mvisual` - Instalador:
`target\installer\Mvisual-Setup-v1.0.0.exe`

### Criar instalador manualmente

Se o Inno Setup 6 estiver instalado e `ISCC.exe` estiver no PATH:

``` bat
ISCC.exe installer.iss
```

### Listar dispositivos DirectShow

A aplicação contém argumentos:

``` bat
Mvisual.exe --list
Mvisual.exe --device "USB Video" --modes
```

Exemplos de execução:

``` bat
Mvisual.exe --device "USB Video" --size 1920x1080 --fps 60
Mvisual.exe --device "USB Video" --audio-device "Microphone (USB Audio)" --size 1920x1080 --fps 60
```

Use os nomes exatos reportados pelo FFmpeg/Windows. Os nomes
`USB Video`, `Cam Link 4K`, `OBS Virtual Camera` e os nomes de áudio
mostrados no launcher são exemplos/predefinições; não significam que
estejam presentes em todos os computadores.

## 5. Plano manual de teste (decidido pelo líder)

O líder pode executar na ordem que preferir. Não tratar os itens como
uma barreira para fornecer código ou pacote.

1.  Abrir o launcher.
2.  Escolher a fonte de vídeo real.
3.  Selecionar entrada de áudio ou "Sem Áudio".
4.  Escolher saída de áudio do Windows.
5.  Testar 720p, 1080p e 4K apenas se a placa de captura suportar os
    modos escolhidos.
6.  Iniciar e observar a janela.
7.  Testar F11 e voltar para janela.
8.  Testar S, setas, M, +, - e Esc.
9.  Conferir imagem, áudio, latência percebida e estabilidade.
10. No Gerenciador de Tarefas, observar GPU, CPU e memória durante uso
    real.
11. Anotar o nome da GPU e o motor gráfico mostrado pelo Windows
    (3D/Video Decode/Compute), resolução de entrada/saída e FPS.

### Diagnóstico de problemas

-   **Janela preta:** conferir se o nome do dispositivo está correto,
    executar `--list` e `--modes`, testar resolução/FPS suportados e
    consultar log/console.
-   **Sem áudio:** conferir `--audio-device`, saída selecionada, formato
    do dispositivo e se a captura está configurada com áudio.
-   **Imagem com barras pretas:** esperado quando se preserva a
    proporção. Para preencher, implementar uma opção separada "Ajustar à
    tela (crop)" ou "Esticar", deixando "Preservar proporção" como
    padrão.
-   **FPS abaixo do esperado:** o FPS real depende da placa de captura e
    do modo de entrada; a renderização não cria quadros novos por si só.
-   **Uso de GPU abaixo de 50%:** não é necessariamente defeito. O
    objetivo deve ser usar a GPU para melhorar imagem/fluidez, não
    desperdiçar processamento para atingir um número fixo.

## 6. Requisitos desejados para o próximo ciclo

### 6.1 Janela e resoluções

-   Manter o modo janela como padrão.
-   Preservar F11 para tela cheia sem bordas e permitir voltar à janela.
-   No modo janela, permitir redimensionamento normal.
-   Oferecer "Automático" e listar dinamicamente os modos de exibição
    suportados pelos monitores do Windows.
-   Considerar monitor selecionado, resolução, refresh rate e DPI.
-   Separar claramente resolução da captura (entrada) e resolução de
    renderização/monitor (saída).
-   Adicionar modo de escala: "Preservar proporção", "Preencher/cortar"
    e, opcionalmente, "Esticar".
-   Evitar barras pretas apenas no modo preencher/cortar, sem alterar
    silenciosamente o padrão.

### 6.2 GPU AMD e NVIDIA

-   Mostrar "Automático", "AMD Radeon", "NVIDIA GeForce/RTX",
    "Intel/outra".
-   A seleção manual deve escolher/solicitar o backend compatível, não
    apenas mudar um texto na UI.
-   Detectar renderer, vendor e versão OpenGL reais via
    `glGetString(GL_VENDOR)`, `GL_RENDERER` e `GL_VERSION`.
-   Se a preferência não for compatível, explicar o motivo e usar
    fallback explícito.
-   Não chamar um filtro GLSL de IA.
-   Manter pipeline base OpenGL para shaders multiplataforma entre GPUs.

### 6.3 IA e processamento gráfico

Implementar em etapas, com recursos opcionais e medição de latência: 1.
**Etapa leve (sem IA):** nitidez adaptativa, FXAA opcional, ajuste de
gama/contraste/saturação, escala bilinear/bicúbica ou shader espacial.
2. **Runtime de IA:** avaliar ONNX Runtime com DirectML como caminho
amplo para GPUs compatíveis no Windows; investigar backend NVIDIA
dedicado (por exemplo, TensorRT) como opção extra. Confirmar suporte
real, versões, licenças e empacotamento antes de prometer
compatibilidade. 3. **Modelo de super-resolução/restauração:**
selecionar modelo adequado para quadros de captura em tempo real;
incluir download/instalação explícita, licença, checksum, tamanho e
perfil de memória. 4. **Integração de GPU:** usar buffers de
textura/interop quando possível; evitar cópias CPU↔GPU em cada quadro;
criar filas e limites de trabalho. 5. **Fluidez:** otimizar a latência
do pipeline, limitar filas e descartar quadros velhos. Interpolação de
quadros é experimental e pode acrescentar latência/artefatos. 6.
**Perfis:** Desempenho, Equilibrado, Qualidade e Personalizado. 7.
**Monitoramento:** mostrar FPS de captura, FPS renderizado, tempo por
quadro, latência estimada, uso de memória e backend ativo, quando
medíveis.

### 6.4 Meta de utilização da GPU

O líder deseja que a GPU trabalhe mais (aproximadamente 50% como
referência), mas não se deve garantir nem forçar esse percentual. Uso
depende de resolução, FPS, modelo, captura e driver. A implementação
deve priorizar melhoria visual mensurável e responsividade, com um
controle de intensidade/perfil e limite de latência.

### 6.5 Áudio

-   Manter seleção explícita da saída.
-   Exibir erros quando a saída selecionada não abrir; não trocar
    silenciosamente para outra saída.
-   Medir/descrever buffering e latência.
-   Evitar estalos, drift e bloqueio da renderização por áudio.

## 7. Estratégia de arquitetura recomendada

Pipeline alvo:

``` text
Placa de captura
   └─ FFmpeg / DirectShow
       ├─ fila curta de vídeo ──> textura GPU ──> shader/IA opcional
       │                                     └─> escala / composição ──> GLFW/OpenGL
       └─ áudio capturado ──> buffer PCM ──> saída de áudio escolhida

Launcher Swing
   ├─ fonte de vídeo / áudio
   ├─ resolução de entrada e saída
   ├─ GPU/backend preferido
   ├─ perfil de qualidade/latência
   └─ filtros e recursos opcionais
```

Regras: - O caminho base deve continuar funcionando sem modelo de IA. -
A IA deve ser opcional e poder ser desligada. - Não bloquear a thread
OpenGL com inferência pesada. - Usar filas pequenas e política de
"quadro mais recente". - Medir antes/depois; não prometer ganho de FPS
ou qualidade sem evidência. - Manter a janela e a interface existente;
não redesenhar o launcher sem necessidade. - Preservar controles e
comportamento de áudio já existentes. - Manter código, scripts e
documentação coerentes entre si.

## 8. Limitações e avisos técnicos

-   FSR, DLSS, XeSS e técnicas de IA têm requisitos específicos. Não são
    intercambiáveis nem basta colocar seus nomes no menu.
-   FSR 1 é uma técnica espacial e pode ser implementada com shaders
    compatíveis, mas não é super-resolução por IA.
-   Recursos ML mais novos podem exigir APIs, runtimes e hardware
    específicos; integração direta com uma pipeline OpenGL existente
    pode demandar interoperabilidade ou arquitetura adicional.
-   "60 FPS" configurado não garante 60 quadros reais se a
    captura/console envia 30 FPS.
-   "4K" de saída não transforma automaticamente uma fonte 1080p em
    detalhe nativo 4K.
-   O uso da GPU a 50% não é uma medida de qualidade; menor uso com
    baixa latência também pode ser um bom resultado.
-   O ZIP-base inspecionado tinha `target` e artefatos antigos. Para
    builds novos, `mvn clean package` deve recriar a saída.
-   A compilação e o hardware real devem ser avaliados pelo líder no
    ambiente Windows dele. Não declarar validação feita se não foi
    executada.

------------------------------------------------------------------------

# 9. Prompt completo para continuar amanhã

Copie o texto abaixo e envie-o em uma nova conversa junto com o ZIP mais
recente do Mvisual.

------------------------------------------------------------------------

Você é o engenheiro responsável por continuar o projeto **Mvisual
Alpha**, um visualizador de captura de vídeo em Java para Windows.
Trabalhe diretamente no ZIP/código que eu anexar. Eu sou o líder do
projeto e decido os testes e a aprovação.

## Contexto do projeto

-   Nome de produto: Mvisual; nome de pacote/classe atual: SwitchPlayer.
-   Java 17, Swing, LWJGL 3, GLFW, OpenGL 3.3, JavaCV/FFmpeg,
    DirectShow, Java Sound, Maven, `jpackage` e Inno Setup 6.
-   Objetivo: simular um monitor/TV gamer em que o usuário possa
    visualizar consoles ou computadores através de uma placa de captura
    HDMI/UVC genérica.
-   GPU principal para desenvolvimento/testes: AMD Radeon RX 9060 XT.
-   O projeto deve funcionar com AMD e NVIDIA, e ter fallback para
    outras GPUs compatíveis.
-   O modo janela é importante: abrir em janela normal, redimensionável;
    F11 alterna para tela cheia sem bordas e permite voltar.
-   Manter o visual e o fluxo do launcher existente, sem redesenhar tudo
    sem necessidade.
-   Manter captura de áudio/vídeo e seleção da saída de áudio.
-   Quero melhorar qualidade da imagem, nitidez, serrilhados, upscaling
    e fluidez, usando IA real quando tecnicamente compatível.
-   Quero poder selecionar no launcher Automático, AMD Radeon, NVIDIA
    GeForce/RTX e Intel/outra GPU.
-   Quero uma opção de resolução automática e uma lista dinâmica de
    resoluções/modos de monitor disponíveis no Windows.
-   Quero uma opção de escala que preserve proporção e outra que
    preencha a janela sem barras pretas usando crop; não distorcer a
    imagem sem que o usuário escolha.
-   Desejo que a GPU seja mais bem utilizada (aproximadamente 50% é uma
    referência desejada, não uma obrigação). Não desperdice
    processamento para atingir um número artificial; priorize qualidade
    e latência.
-   Eu pedi IA de verdade, não apenas filtros com o rótulo "IA".

## Regras obrigatórias

1.  Primeiro, inspecione o projeto anexado e o código real. Não presuma
    que mudanças mencionadas em conversas anteriores estejam presentes
    no ZIP.
2.  Preserve funcionalidades existentes que já funcionam; faça
    alterações localizadas.
3.  Não apresente filtro GLSL como IA.
4.  Não invente resultados de compilação ou testes. Se não executar um
    teste, diga claramente que não executou, mas não bloqueie a entrega
    por isso.
5.  Não espere que eu valide antes de fornecer os arquivos. Entregue o
    pacote de trabalho e os comandos; eu sou responsável pela validação
    final.
6.  Forneça arquivos completos modificados, não só fragmentos, quando
    isso for necessário para eu substituir os arquivos.
7.  Gere um ZIP final com o projeto atualizado e um documento
    README/CHANGELOG que descreva o que realmente foi alterado.
8.  Não remova o modo janela, o launcher, a seleção de áudio, os atalhos
    existentes ou os scripts de build.
9.  Não garanta 50% de uso de GPU, FPS ou latência. Exponha métricas e
    perfis reais.
10. Se uma integração de IA exigir runtime ou modelo externo, implemente
    carregamento/configuração opcionais, documente dependências,
    licenças, tamanho e instruções. O modo sem IA deve continuar
    funcionando.
11. Antes de editar, identifique quais funcionalidades já existem e
    quais são apenas ideias pendentes.
12. Entregue também comandos CMD/PowerShell para build, execução,
    diagnóstico de dispositivos e criação do instalador, além da lista
    atualizada de teclas.

## Trabalho a executar

A. Revisar a estrutura do projeto e `SwitchPlayerApp.java`, `pom.xml`,
`build.bat`, `installer.iss` e README. B. Corrigir/integrar o
launcher: - resolução "Automática" e enumeração dinâmica de modos de
monitor; - distinguir resolução da fonte capturada e resolução de
saída; - preferência de GPU: Automático/AMD/NVIDIA/Intel-outra; - perfis
Desempenho/Equilibrado/Qualidade/Personalizado; - filtros
selecionáveis; - escala Preservar proporção/Preencher com
crop/Esticar; - manter áudio e captura atuais. C. Exibir a GPU/backend
real detectado (vendor, renderer, versão OpenGL), avisar quando a
preferência manual não puder ser aplicada e fornecer fallback. D.
Implementar filtros gráficos práticos e leves no OpenGL: nitidez
adaptativa ajustável, FXAA opcional, controle de
gama/contraste/saturação e escala espacial. Manter a IA separada desses
filtros. E. Avaliar e implementar uma arquitetura de inferência real com
ONNX Runtime + DirectML para compatibilidade ampla no Windows e, quando
viável, backend NVIDIA dedicado. Não afirmar suporte a backend sem
confirmar API, dependências e hardware. Evitar cópias de quadro
desnecessárias e manter filas curtas. F. Se viável, integrar um modelo
de super-resolução/restauração apropriado para vídeo em tempo real.
Informar se o modelo é opcional, como obtê-lo, licença, tamanho e custo
estimado de VRAM. Se não for viável neste ciclo, deixar uma interface de
backend bem definida e documentação honesta, sem fingir que a IA já
funciona. G. Otimizar a fluidez: medir FPS de entrada e renderização,
manter a política de quadros mais recentes, reduzir filas e avaliar
buffering. Interpolação de quadros deve ser opcional/experimental,
porque pode adicionar latência. H. Preservar controles atuais: - F11:
alternar janela/tela cheia sem bordas; - S: ligar/desligar nitidez; -
seta para cima/baixo: ajustar nitidez; - M: mute; - +/= e -: volume; -
Esc: sair. Se adicionar atalhos, documentá-los e evitar conflitos. I.
Atualizar build/instalador e produzir ZIP final. Incluir os comandos de
teste e uma checklist que eu possa executar. J. Criar
`DOCUMENTACAO_CONTINUIDADE_MVISUAL.md` com o estado real, alterações,
comandos, teclas, problemas conhecidos e próximas etapas.

## Entrega final obrigatória

-   ZIP completo com o código-fonte atualizado.
-   Documentação Markdown.
-   Lista objetiva do que foi implementado nesta rodada.
-   Lista do que permanece pendente.
-   Comandos exatos para compilar e gerar o instalador no Windows.
-   Comandos/argumentos para listar dispositivos DirectShow e modos de
    captura.
-   Teclas e plano de teste manual.
-   Não afirmar que o instalador, a captura, o áudio ou a IA foram
    testados se não foram executados.
-   Entregar os arquivos sem exigir minha aprovação prévia.

Comece inspecionando o ZIP que eu anexar e continue a partir do código
real, não de suposições.

------------------------------------------------------------------------

## 10. Próxima sessão --- checklist rápida

-   [ ] Anexar o ZIP mais recente do projeto.
-   [ ] Anexar este documento ou copiar o prompt da seção 9.
-   [ ] Dizer quais testes você já executou e os resultados/erros, se
    quiser que eu corrija problemas específicos.
-   [ ] Prioridade sugerida: resolução/modos de janela → seleção e
    detecção de GPU → filtros GLSL e métricas → runtime/modelo de IA →
    medição de latência e instalador.


------------------------------------------------------------------------

## 11. Atualização de continuidade — IA Alpha e janela com barra de controles

**Última atualização:** 20/10/2026. Esta seção registra o estado conhecido após a geração do pacote `Mvisual-Alpha-v1.1.0-AI-SUPER-RESOLUTION.zip` e o pedido de uma interface de monitor semelhante ao VisualBoyAdvance mostrado nas imagens do líder.

### 11.1 Pacote recente e estado real

O pacote mais recente mencionado nesta conversa é `Mvisual-Alpha-v1.1.0-AI-SUPER-RESOLUTION.zip`. Ele adiciona ao código:

- `AiSuperResolution.java`, com integração de inferência via ONNX Runtime.
- Dependência Maven `com.microsoft.onnxruntime:onnxruntime:1.22.0`.
- Opção de super-resolução por IA no launcher e preferência de backend de GPU.
- Tentativas de provider CUDA/DirectML, com fallback para CPU quando não disponíveis.
- Ajustes de shader para parâmetros de saturação, FXAA e escala UV.
- Documentação de setup e pasta `models/` com instruções para o modelo.

**Limitações importantes:** os pesos do modelo não estão incluídos. O arquivo esperado é `Mvisual/models/realesrgan-x2plus.onnx`, compatível com a forma de tensor/normalização esperada pelo código. O artefato Java padrão do ONNX Runtime não garante que os providers nativos CUDA ou DirectML estejam instalados e funcionais. A execução de IA na AMD Radeon RX 9060 XT, a compilação completa, a captura real e o desempenho a 60 FPS não foram confirmados nesta sessão. Não apresentar esses pontos como concluídos.

Também revisar o código real do ZIP mais recente antes de continuar: podem existir diferenças entre o ZIP Alpha original, `IMAGE-ENHANCEMENTS` e `AI-SUPER-RESOLUTION`. Não misturar arquivos de pacotes diferentes sem comparar a versão.

### 11.2 Pedido novo: barra superior integrada à janela do monitor

O líder quer que a janela de exibição tenha uma barra de menus/controles semelhante ao VisualBoyAdvance da imagem de referência, mantendo o vídeo do jogo na mesma janela. Isso é um **requisito solicitado, ainda não uma funcionalidade implementada ou validada**.

Estrutura desejada:

- **Arquivo:** reiniciar a captura, reconectar dispositivos quando possível e sair.
- **Vídeo:** resolução, escala, proporção e tela cheia.
- **Filtros:** nitidez, FXAA, saturação e super-resolução por IA.
- **Áudio:** dispositivo de saída, volume e mute.
- **Opções/Configurações:** perfil de qualidade/latência, GPU/backend e preferências.
- **Ações rápidas:** botão `Reiniciar`, botão `Filtros`, áudio e configurações, se isso couber sem poluir a interface.
- **Área de jogo:** ocupar o máximo de espaço útil, redimensionar junto com a janela e preservar a proporção conforme o modo escolhido.

Comportamento requerido:

1. No modo janela, mostrar a barra de controles e a área de vídeo dentro da mesma janela do monitor.
2. `Reiniciar` deve reiniciar a captura/fluxo de dispositivos, não encerrar e relançar o aplicativo inteiro sem necessidade.
3. Alterar filtros deve atualizar os parâmetros da renderização em tempo real sempre que possível, sem reiniciar a captura.
4. `F11` deve alternar para tela cheia sem bordas e ocultar a barra durante a tela cheia; ao retornar ao modo janela, a barra reaparece.
5. Manter a opção de preservar proporção, preencher com corte (crop) e esticar. Não deformar a imagem silenciosamente.
6. Não remover os controles de teclado existentes nem quebrar o áudio/captura.
7. Se a janela atual usa GLFW/OpenGL, escolher uma solução compatível com essa arquitetura. Uma barra Swing separada não atende ao pedido de controles integrados à mesma janela; avaliar menus nativos do GLFW, overlay OpenGL ou uma arquitetura híbrida cuidadosamente sincronizada.

### 11.3 Atalhos conhecidos

- `F11`: alternar janela/tela cheia sem bordas.
- `S`: ligar/desligar nitidez.
- `↑` / `↓`: ajustar nitidez.
- `M`: silenciar/reativar áudio.
- `+` / `=` e `-`: ajustar volume.
- `Esc`: fechar a janela/monitor.

A barra deve exibir as ações mais importantes e não substituir esses atalhos. Ao implementar novos atalhos, documentá-los e impedir conflito com os existentes.

### 11.4 Comandos de Debug e build

Na pasta raiz do projeto `Mvisual`, no terminal da IDE ou PowerShell:

```bat
java -version
mvn -version
mvn clean package
```

Também é possível usar `build.bat` no Windows. Para executar no Debug da IDE, importar o projeto como Maven, aguardar a resolução das dependências e iniciar `com.switchplayer.SwitchPlayerApp`. A execução pelo Debug é um modo de execução, não uma garantia de que todas as dependências nativas, dispositivos ou backends de IA estejam disponíveis.

Avisos `swscaler ... deprecated pixel format used, make sure you did set range correctly` são avisos do FFmpeg/libswscale sobre formato/range de pixel; não são, isoladamente, prova de falha fatal. Registrar o contexto e verificar se vídeo, cores e níveis estão corretos antes de decidir se é necessário alterar o pipeline.

### 11.5 Próximas tarefas, em ordem

1. **Abrir o ZIP realmente usado no Debug** e inspecionar a versão de `SwitchPlayerApp.java`, `pom.xml`, shaders e recursos. O líder ainda precisa anexar esse ZIP para que as alterações sejam aplicadas à base correta.
2. Implementar a barra de menus/controles na própria janela do monitor, mantendo o comportamento atual da área de vídeo.
3. Conectar `Reiniciar` ao ciclo de vida de captura, com tratamento de erro e recuperação dos dispositivos.
4. Conectar controles de filtros aos uniforms/parâmetros reais do shader e deixar explícito quando uma função não estiver disponível.
5. Garantir que a barra suma/reapareça com `F11` e que o redimensionamento não distorça a imagem.
6. Corrigir a experiência do launcher se os controles adicionados ultrapassarem a área visível; preferir layout responsivo/rolagem a cortar opções.
7. Depois, confirmar o carregamento do modelo ONNX, provider real (DirectML/CUDA/CPU), dimensões e normalização do modelo, consumo de memória, latência e FPS.
8. Atualizar `README.md`, changelog, `build.bat`, instalador e esta documentação para refletir exatamente o código entregue.

### 11.6 Prompt atualizado para retomar o projeto

> Continue o projeto **Mvisual Alpha** usando o ZIP mais recente que eu anexar — esse ZIP é a fonte de verdade. Eu sou o líder do projeto e decido a validação; não bloqueie a entrega esperando minha aprovação prévia. Preserve a captura de vídeo/áudio, o launcher, a janela GLFW/OpenGL e os atalhos existentes. Minha nova prioridade é uma barra de menus e controles integrada à própria janela do monitor, no estilo do VisualBoyAdvance mostrado na referência: Arquivo, Vídeo, Filtros, Áudio e Opções, além de ações rápidas para Reiniciar e Filtros. Reiniciar deve reiniciar a captura; filtros devem ser ajustáveis durante o uso quando possível. A barra aparece no modo janela e fica oculta no fullscreen F11, reaparecendo ao voltar. Preserve proporção, ofereça preencher com crop/esticar quando implementado e evite deformação. Não afirme que a IA funciona só porque existe uma classe ONNX: confira o modelo, os providers nativos e o fallback real. Os pesos do modelo não vêm no ZIP e a execução de IA na RX 9060 XT/60 FPS ainda não foi comprovada. Inspecione o código e dependências reais, faça as alterações diretamente nele, entregue o projeto/ZIP e a documentação atualizada, liste os comandos de Debug/build e os atalhos, e seja claro sobre o que foi implementado versus o que não foi testado.
