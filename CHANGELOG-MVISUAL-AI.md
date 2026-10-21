# Mvisual Alpha — integração experimental de super-resolução neural

- Adicionada classe AiSuperResolution com inferência ONNX Runtime para modelos float RGB NCHW.
- Adicionada opção no launcher para ativar IA neural.
- Preferência AMD/NVIDIA tenta DirectML/CUDA e usa CPU como fallback quando o provider não existe.
- Captura pode publicar o quadro resultante do modelo em resolução maior para o renderer OpenGL.
- IA desativada por padrão; requer `models/realesrgan-x2plus.onnx`, não incluído no pacote.
- Nota: modelo x2 de quadro inteiro pode não atingir 60 FPS; otimização por tiles e interoperabilidade GPU ainda são melhorias futuras.
