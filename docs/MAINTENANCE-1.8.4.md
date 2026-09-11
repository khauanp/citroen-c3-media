# C3 Media 1.8.4 — manutenção de rota e mídia

## Base preservada

O tablet continua usando exclusivamente `C3-Media-1.8.1-K00E.apk`, SHA-256 `be86b35046e09ca900bd0acb30b47101e40a72e7ef0fc33f1a6085c26d9ccb3f`.

A verificação automática desmonta a base e o APK reconstruído e exige o mesmo conjunto de classes, recursos, assets, manifesto e bibliotecas nativas. Somente quatro métodos podem mudar:

- `C3LinkServer.checkTimeout()`, para não descartar a rota durante pausas curtas;
- `DashboardView.drawNavigation()`, para desenhar a rota por segmentos visíveis;
- `AudioRenderer.attachEngine()`, alterando apenas o percentil adaptativo de 95 para 99;
- `VideoRenderer._feedToCodec()`, alterando apenas três constantes de espera/tentativa da fila.

Versão final: `1.8.4` (`versionCode 10804`). Nenhum layout, recurso visual, comando de música ou biblioteca nativa é substituído.

## Linha azul

O modo de navegação do tablet somente é ativado quando a rota contém pelo menos dois pontos. Como o painel exibia destino, tempo e instrução, mas nenhuma linha, a falha estava na renderização do `Path`, não na confirmação do pacote.

A 1.8.4 mantém o `Path` original como fallback e também desenha diretamente cada segmento que cruza a área visível. Cada segmento recebe contorno escuro de 24 px e azul de 12 px. Pontos, projeção Web Mercator, rotação do mapa e posição GPS continuam os mesmos.

## Áudio e espelhamento

- O áudio continua adaptativo, mas usa o percentil 99 do jitter observado, absorvendo picos mais raros antes de produzir silêncio.
- O vídeo passa de 3 tentativas de 2 ms para 4 tentativas de 4 ms; no primeiro quadro, de 15 para 20 tentativas. Isso reduz descartes por fila momentaneamente cheia sem manter o callback bloqueado por mais de um quadro normal.
- Codec, resolução, cor, enquadramento e continuidade da sessão não mudam.

## Teste físico obrigatório

1. Instale a C3 Media 1.8.4 sobre a 1.8.3.
2. Teste uma rota curta e uma longa; confirme a linha azul desde o marcador atual e após deslocamento.
3. Bloqueie o iPhone por cinco minutos e confirme GPS, rota e instruções atualizados.
4. Reproduza música por pelo menos 15 minutos e observe cortes, play/pausa, troca de faixa, capa e metadados.
5. Espelhe o iPhone, alterne orientação e abra/feche o espelhamento; confirme imagem, som e que o app não reinicia.

CI e comparação binária não substituem o teste no ASUS K00E e no iPhone reais.
