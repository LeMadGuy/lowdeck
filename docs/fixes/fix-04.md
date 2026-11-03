**FIX-04: Tela Remota (Máquina de Estados e Fallback Resiliente)**
   - Máquina de estados no daemon que limpa e fecha os descritores de ficheiro caso o portal de ScreenCast falhe ou seja cancelado.
   - Fallback automático de encoder: VA-API/NVENC -> CPU (`x264enc tune=zerolatency speed-preset=ultrafast`).
   - Reconexão limpa no cliente sem necessidade de reiniciar o servidor.