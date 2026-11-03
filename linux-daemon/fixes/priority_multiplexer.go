package fixes

import (
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// FIX-05: Picos de Latência de ~430ms por todo o App
//
// Causa Raiz Identificada:
// Head-of-Line (HoL) Blocking no canal TCP. Quando o Terminal ou a Tela Remota enviavam centenas
// de kilobytes pelo mesmo socket, o TCP garantia entrega ordenada estrita. Os pacotes leves (Ping de
// medição e deltas de Input) ficavam represados na fila do socket atrás dos blocos de dados pesados,
// aguardando janelas de confirmação TCP (Delayed ACK típico de 200ms a 500ms).
//
// Solução Implementada:
// Criação de duas filas no servidor (PriorityMultiplexer). A fila de Controle/Input/Ping tem prioridade
// estrita e bypass total sobre a fila de Dados Pesados (PTY/Mídia). O Ping é respondido instantaneamente
// na camada de rede sem nenhum enfileiramento.

// PriorityMultiplexer alias for server.PriorityMultiplexer.
type PriorityMultiplexer = server.PriorityMultiplexer

// NewPriorityMultiplexer creates a new dual-queue priority multiplexer.
func NewPriorityMultiplexer(ctrlCap, bulkCap int) *PriorityMultiplexer {
	return server.NewPriorityMultiplexer(ctrlCap, bulkCap)
}

// IsHighPriority returns true if an envelope belongs to the high-priority bypass queue.
func IsHighPriority(env *pb.Envelope) bool {
	return server.IsHighPriority(env)
}

