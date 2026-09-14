# C3 Media 1.8.21 — estabilização somente de mídia

## Escopo autorizado

A versão 1.8.21 será reconstruída como receptor AirPlay de mídia e espelhamento, sem navegação própria.

Permanecem:

- áudio AirPlay do iPhone e saída Android pelo cabo auxiliar;
- metadados enviados pelo iPhone: faixa, artista, álbum, capa, duração e progresso;
- espelhamento AirPlay de qualquer aplicativo, inclusive Waze;
- rotação automática, aspect-fit sem deformação e reinício seguro do decoder;
- splash Citroën, interface 1280x800, paisagem, boot, launcher e quiosque;
- espera, proteção térmica 45/41 °C, bateria/memória e recuperação;
- hotspot Citroen-C3 e fallback por Wi-Fi compartilhado.

Serão removidos do APK final:

- mapa desenhado pelo tablet;
- tiles, MBTiles e cache de mapa;
- rota, polilinha, GPS de navegação, radar e limite de velocidade;
- servidor HTTP de rota, WebView Waze e proxy de recursos;
- controles de música na tela e DACP, pois não fazem parte do escopo final.

## Falha física prioritária

No fim natural de uma faixa, o iPhone envia uma pausa/teardown transitória antes da próxima sessão de áudio. O tablet não pode interpretar isso como fim da conexão, parar o renderer, liberar o servidor ou encerrar a Activity. A mesma regra vale para bloqueio de tela, notificações, ligações, rotação, alternância de aplicativo e perda breve de pacotes.

## Critérios antes da entrega

1. Nenhum componente de mapa/rota/proxy no DEX, manifesto ou recursos.
2. Pausa, teardown e zero conexões transitórios não desmontam áudio, vídeo ou serviço.
3. Somente uma limpeza confirmada e atrasada pode liberar recursos de sessão.
4. Metadados/capas são serializados, limitados e não reciclam bitmap ainda desenhado.
5. Exceções de AudioTrack, MediaCodec, NSD, callbacks e listeners ficam contidas.
6. Serviço permanece START_STICKY e Activity é recuperada após falha Java.
7. APK Android 5/x86 reconstruído e validado contra a base de mídia.
8. Teste físico continua obrigatório: CI não reproduz o codec Intel nem o comportamento real do iPhone.

Checkpoint criado em 14 de setembro de 2026.
