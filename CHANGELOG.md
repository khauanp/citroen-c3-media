# Changelog

## 1.8.5 — 2026-08-21

- corrige a regressão da 1.8.4 que invertia o filtro de segmentos e descartava a rota visível;
- impede que coordenadas muito distantes cheguem ao `Canvas.drawLine`, eliminando os quadrados pretos produzidos pela GPU antiga do K00E;
- desenha em azul somente segmentos com os dois pontos dentro de uma margem segura ao redor da tela, mantendo o `Path` original como fallback;
- mantém sem novas alterações o design, o player, o mapa, o GPS, o áudio e o espelhamento que já funcionavam na 1.8.4;
- continua sendo reconstruída diretamente sobre o APK real C3 Media 1.8.1.

## 1.8.4 — 2026-08-21

- mantém integralmente os recursos e a interface da C3 Media 1.8.1;
- desenha cada segmento visível da rota diretamente no `Canvas`, com contorno e linha azul, evitando a falha do `Path` no Android 5/K00E;
- preserva o desenho original da rota como fallback e mantém a projeção, o GPS e as coordenadas sem alteração;
- aumenta o percentil adaptativo do buffer de áudio de 95 para 99 para tolerar mais jitter sem cortes;
- aumenta moderadamente a tolerância da fila do decodificador de vídeo antes de descartar um quadro, sem elevar resolução ou carga gráfica;
- mantém a rota ativa durante pausas curtas da conexão C3 Link.

## 1.8.3 — 2026-08-21

- usa o APK real `C3-Media-1.8.1-K00E.apk` como base binária do tablet; o código Android antigo do repositório não participa do build;
- preserva, por comparação automática de todo o APK desmontado, a interface, o player, os recursos e a biblioteca nativa da 1.8.1;
- mantém a rota ativa no tablet durante pausas curtas do UDP, sem alterar os comandos explícitos de encerrar navegação;
- envia a rota completa e também em partes pequenas, espaçadas e reenviadas, para que a linha azul não dependa de um único pacote;
- mantém o GPS de navegação do iPhone em alta precisão, inclusive com a tela apagada, e reforça rota e posição a cada 15 segundos;
- aumenta o contraste dos mesmos tiles 2D do OpenStreetMap antes de transmiti-los, sem exigir renderização 3D do K00E;
- adiciona um ícone próprio ao C3 Link no iPhone.

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
