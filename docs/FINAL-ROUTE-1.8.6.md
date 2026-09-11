# C3 Media + C3 Link 1.8.6 — geometria completa da rota

## Evidência do teste físico

Na 1.8.5 a linha finalmente apareceu sem quadrados pretos, mas o teste revelou dois defeitos restantes:

1. no próprio iPhone, trechos azuis ligavam pontos distantes por retas que atravessavam quadras sem rua;
2. no tablet, a linha terminava antes de sair da área visível, apesar de a rota completa estar confirmada.

## Causa da geometria incorreta

O C3 Link recebia a geometria completa do OSRM, mas reduzia qualquer rota com mais de 1.200 pontos ou 8.000 bytes por amostragem uniforme. Em uma rota de 154,6 km, pontos importantes de curvas e esquinas podiam ser descartados. O mapa então ligava os pontos restantes com retas, criando um traçado visual inseguro que não acompanhava as ruas.

A 1.8.6 mantém todos os pontos originais para a visualização do iPhone, acompanhamento do GPS e envio ao tablet enquanto o pacote permanecer dentro de 26.000 bytes e 10.000 pontos. Se uma rota excepcional ultrapassar esses limites, utiliza Ramer–Douglas–Peucker em coordenadas projetadas para metros. Esse algoritmo remove redundância mantendo as mudanças reais de direção; a amostragem uniforme foi eliminada.

## Causa do corte no tablet

O `Canvas` do K00E é recortado em 1280×800 e depois rotacionado ao redor do marcador em `(640, 620)`. A margem da 1.8.5 cobria a largura rotacionada, mas não toda a altura possível. Segmentos ainda visíveis depois da rotação podiam ser descartados.

A 1.8.6 usa o envelope `-512…1792` nos dois eixos. Um teste percorre todos os 360 graus e comprova que os quatro cantos da tela cabem nesse envelope. Coordenadas extremas continuam bloqueadas, impedindo o retorno dos quadrados pretos.

## Escopo preservado

- Android continua reconstruído sobre o APK real 1.8.1;
- nenhum layout, recurso, player, comando de música, codec ou biblioteca nativa muda;
- ajustes de áudio, vídeo e permanência da rota da 1.8.4 são mantidos;
- interface e ícone do C3 Link permanecem iguais;
- somente geração/transporte da geometria e envelope do desenho da linha são alterados.

Versões: C3 Media `1.8.6` (`versionCode 10806`) e C3 Link `1.8.6` (`build 9`).

## Validação física segura

Faça o primeiro teste com o carro parado. Compare a linha do C3 Link e do tablet com as ruas visíveis no mapa. Somente depois de confirmar que o traçado acompanha as vias e continua até a borda da tela faça um percurso curto e conhecido. O aplicativo não substitui atenção à sinalização, às condições reais da via e às instruções oficiais do Waze/Google Maps.
