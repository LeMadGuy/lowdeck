Picos de Latência de ~430ms por todo o App
* **Causa Raiz Identificada:** Head-of-Line (HoL) Blocking no canal TCP. Quando o Terminal ou a Tela Remota enviavam centenas de kilobytes pelo mesmo socket, o TCP garantia entrega ordenada estrita. Os pacotes leves (Ping de medição e deltas de Input) ficavam represados na fila do socket atrás dos blocos de dados pesados, aguardando janelas de confirmação TCP (Delayed ACK típico de 200ms a 500ms).
* **Solução nos Fixes:**
  - `linux-daemon/fixes/priority_multiplexer.go`: Criação de duas filas no servidor. A fila de **Controle/Input/Ping** tem prioridade estrita e bypass total sobre a fila de **Dados Pesados (PTY/Mídia)**. O Ping é respondido instantaneamente na camada de rede sem enfileiramento.
