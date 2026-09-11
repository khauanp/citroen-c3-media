# C3 Media 1.8.3 — correção somente do mapa

## Regra de preservação

O tablet usa como única base `C3-Media-1.8.1-K00E.apk`, SHA-256 `be86b35046e09ca900bd0acb30b47101e40a72e7ef0fc33f1a6085c26d9ccb3f`. O projeto Android histórico presente no repositório não entra no build.

A verificação desmonta tanto a base quanto o APK reconstruído e exige:

- o mesmo conjunto de classes;
- todos os recursos, assets e bibliotecas nativas idênticos;
- nenhuma diferença no código da interface ou do player;
- somente `C3LinkServer.checkTimeout()` diferente, para preservar a rota durante pausas temporárias da conexão;
- versão final `1.8.3` (`versionCode 10803`).

Os comandos explícitos `stop` e `goodbye` continuam encerrando a navegação normalmente.

## Alterações do mapa

- O iPhone envia primeiro a rota completa e, quando suportado, envia uma segunda cópia em partes de 480 bytes com intervalo de 8 ms. Isso reduz perda por rajada UDP.
- Enquanto a navegação estiver ativa, o C3 Link reforça a rota e a última posição a cada 15 segundos.
- O filtro de distância do GPS durante a rota passa de 4 m para 2 m, com `BestForNavigation`, atualização em segundo plano e pausa automática desativada.
- Os tiles continuam vindo do OpenStreetMap e continuam 2D. O iPhone aplica contraste e tonalidade mais escura antes do envio, poupando CPU e memória do tablet.
- Os chunks dos tiles são espaçados para que pacotes de GPS e rota tenham oportunidade de passar entre eles.
- O C3 Link recebe um AppIcon próprio; nenhuma tela do iPhone foi redesenhada.

## Teste físico obrigatório

1. Atualize a C3 Media e abra música antes de iniciar uma rota; valide play, pausa, anterior, próxima, capa e metadados.
2. Inicie uma rota longa no C3 Link e aguarde a confirmação da rota no tablet.
3. Confirme a linha azul inteira sobre o mapa e o contraste das ruas.
4. Bloqueie o iPhone por pelo menos cinco minutos e dirija ou simule deslocamento; confirme posição e instruções atualizadas.
5. Encerre apenas a rota; confirme que o painel volta normalmente e que o app não reinicia.
6. Inicie e pare o espelhamento AirPlay; confirme que a interface e o player continuam funcionais.

Builds de CI não substituem este teste no ASUS K00E e no iPhone reais.
