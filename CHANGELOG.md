# Changelog

## 1.8.2 — 2026-08-21

- reconstrói e publica no repositório a fonte Android correspondente à linha 1.8.x;
- encerra o espelhamento sem destruir o `MediaCodec` de forma síncrona, evitando o fechamento e relançamento do painel no K00E;
- mantém receptor AirPlay, C3 Link, rota e Activity independentes entre sessões de vídeo;
- persiste a rota no tablet e a desenha sempre acima do mapa, inclusive enquanto os tiles carregam ou são recuperados;
- reforça entrega UDP com montagem fora de ordem, confirmação, solicitação automática e reafirmação periódica da rota;
- suaviza posição e direção no tablet usando continuamente o GPS do iPhone;
- adota mapa 2D CARTO Voyager com maior contraste e leitura, sem o custo de renderização 3D;
- reduz carga do K00E com cache RGB565, limite de memória, pré-carregamento controlado e rota limitada a 2.000 pontos por quadro;
- adiciona ícone próprio completo ao C3 Link no iPhone;
- atualiza C3 Link para 1.8.2 (build 6) e C3 Media para `versionCode 10802`.

## 1.2.0 — 2026-08-18

- corrige a troca Waze → YouTube/YouTube Music encerrando e zerando o `MediaCodec` antigo antes da nova sessão;
- adota painel modular horizontal: Waze em card proporcional e mídia em card lateral funcional;
- entra em espera após 45 segundos sem iPhone, com tela quase preta e descarte de vídeo, áudio e capa;
- detecta o retorno do iPhone pela rede e reproduz novamente a animação Citroën;
- monitora temperatura, carga e memória; reduz brilho e capas sob proteção térmica/pressão de memória;
- registra falhas Java, relança a central automaticamente e oferece o diagnóstico no menu técnico;
- preserva o receptor mínimo de rede durante a espera para permitir reconexão.

## 1.1.0 — 2026-08-18

- mantém o Waze visível quando YouTube Music/Spotify enviam metadados ou progresso;
- reduz o uso de memória das capas e ignora artes inválidas para evitar encerramento no Android 5;
- gira automaticamente vídeo vertical para o painel paisagem;
- aplica `aspect fit` com barras pretas, sem alongamento ou corte da imagem;
- reinicia o decodificador de forma segura quando o iPhone muda de orientação;
- aumenta fontes, áreas de toque e controles de música sobre o mapa;
- adiciona cartões tocáveis com ajuda de rede, Waze e música;
- documenta IP manual sem roteador para manter 4G/5G no iPhone durante o AirPlay.

## 1.0.0 — 2026-08-18

- primeira versão específica para ASUS K00E / Android 5 / x86;
- recepção de áudio AirPlay para YouTube Music e Spotify;
- espelhamento H.264 para Waze;
- metadados, capa, progresso e controles DACP;
- saída de mídia para rádio Bluetooth pelo roteamento do Android;
- interface C3 Media, splash Citroën, launcher e boot automático;
- ponto de acesso local e menu técnico protegido;
- correções de OpenSSL e JNI necessárias no Android 5.
