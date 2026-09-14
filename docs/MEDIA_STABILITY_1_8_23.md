# C3 Media 1.8.23 — tolerância no fim de playlists e exportação do relatório

## Relato físico que motivou a manutenção

No ASUS K00E, a 1.8.22 manteve sessões AirPlay por várias músicas, mas ainda
podia encerrar no fim de uma playlist e, com menor frequência, durante outras
trocas. O relatório era detectado corretamente, porém o modo quiosque recolocava
o C3 Media acima do seletor de arquivos e impedia a exportação.

## Proteções adicionais do áudio e da mídia

- formatos vazios ou incompatíveis de fim de fila não chegam ao decoder nativo;
- formatos idênticos entre faixas não reconfiguram o decoder desnecessariamente;
- metadados têm limite de tamanho, profundidade, quantidade de campos e texto;
- o parser DMAP não copia cada payload recebido;
- a capa recebida pelo JNI deixa de ser duplicada antes da decodificação;
- uma nova faixa libera a referência visual da capa anterior enquanto aguarda a
  próxima imagem;
- pressão crítica de heap ou memória cancela capas pendentes e mantém o áudio;
- valores de progresso inválidos, invertidos ou excessivos são ignorados;
- teardown e desconexões transitórias continuam usando as tolerâncias da 1.8.22.

A animação do disco foi mantida: ela reutiliza o mesmo bitmap e não foi a origem
do crescimento de memória. O custo removido estava na cópia e decodificação das
capas durante as transições.

## Seletor de relatório fora do modo quiosque

Ao tocar em **Escolher onde salvar**, o app agora suspende temporariamente o
`lock task` e o retorno automático ao primeiro plano. O seletor de documentos do
Android fica livre para receber o toque. Ao salvar ou cancelar, o C3 Media volta
à tela, restaura a interface imersiva e reativa o modo quiosque configurado.

Se a gravação for cancelada ou falhar, o relatório permanece armazenado no app.

## Compatibilidade preservada

A pilha nativa x86 validada fisicamente nas versões estáveis continua byte a
byte igual. Permanecem também a interface em tela cheia, tema automático,
espelhamento proporcional, player sem controles no tablet, botão fechar,
hotspot `Citroen-C3`, áudio pelo cabo auxiliar, inicialização automática e o
diagnóstico persistente de encerramentos.
