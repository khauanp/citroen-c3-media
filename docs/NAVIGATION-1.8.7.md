# C3 Media + C3 Link 1.8.7 — rota integral, radar e velocidade

## O que o teste físico revelou

Uma rota curta ficou correta, enquanto a viagem de 154,6 km continuou com segmentos errados ou aparentemente encerrados. Isso isolou o defeito no caminho usado somente por rotas grandes: a 1.8.6 reduzia a geometria para caber em 28.000 bytes e o tablet aceitava até 12.000 pontos, mas seu decodificador devolvia silenciosamente uma lista parcial ao atingir esse limite. Mesmo incompleta, a rota recebia `route-ok`.

## Transporte integral e confirmação real

A 1.8.7 não simplifica a rota por quantidade de pontos ou bytes. O traçado automobilístico do Apple MapKit é densificado sem mudar sua forma, mantendo no máximo 60 m entre pontos consecutivos. Isso impede que um segmento reto muito longo seja descartado pelo envelope seguro da GPU do K00E.

O protocolo aceita até 512 partes, 350.000 bytes e 50.000 pontos. O identificador contém CRC32 e contagem de pontos. O tablet exige simultaneamente:

- recebimento de todas as partes;
- consumo integral da polilinha pelo decodificador;
- contagem exata de pontos;
- CRC32 idêntico ao calculado no iPhone.

Somente depois dessas quatro verificações o tablet envia `route-ok`. A mensagem do iPhone “Rota azul confirmada no tablet” deixa de significar apenas que algum prefixo foi aceito.

## Renderização segura no Android 5

Os dois desenhos de `Path` que enviavam a viagem inteira à GPU foram removidos. A linha continua com contorno escuro e centro azul, mas somente segmentos locais dentro do envelope rotacionado são desenhados. A geometria densa garante continuidade até a borda e evita os quadrados pretos vistos no K00E.

## Mãos de rua e coordenadas

- o pedido de rota usa `MKDirections` com transporte `.automobile`;
- o GPS ignora amostras com mais de 10 segundos ou erro horizontal acima de 65 m;
- em navegação, usa `kCLLocationAccuracyBestForNavigation`, atualização a cada 2 m e localização em segundo plano;
- a velocidade é suavizada, mas a posição enviada continua sendo a coordenada GPS válida do iPhone;
- recálculo ocorre após desvios confirmados, não por uma única leitura imprecisa.

O serviço de rota usa as restrições viárias cadastradas na base Apple. Sinalização e condições reais da via continuam soberanas.

## Radares e limite

O iPhone consulta o OpenStreetMap por Overpass somente durante a navegação:

- `highway=speed_camera` e `enforcement=maxspeed` para radares;
- `maxspeed=*` em vias próximas para o limite;
- filtro de 120 m ao redor da geometria real para não importar radares de vias distantes;
- correspondência por proximidade e sentido da via para escolher o limite atual;
- cache e intervalo mínimo para não consultar o serviço continuamente.

O mapa do iPhone mostra os radares encontrados. Para preservar desempenho no K00E, o tablet recebe o próximo radar relevante, desenha seu ponto no mapa e mostra a distância. O limite conhecido aparece ao lado do velocímetro; acima de `limite + 2 km/h`, o velocímetro fica vermelho.

Dados de radar e limite podem estar ausentes ou desatualizados no OpenStreetMap. Quando o limite não é conhecido, a tela não inventa um valor e não gera alerta vermelho.

## Escopo preservado

O Android continua sendo reconstruído a partir do APK 1.8.1 exato. Recursos, layout, player, manifesto, bibliotecas nativas, controles de música e codecs são comparados contra essa base. Permanecem também os ajustes de estabilidade de áudio e vídeo das manutenções anteriores.

Versões: C3 Media `1.8.7` (`versionCode 10807`) e C3 Link `1.8.7` (`build 10`).

## Critério de liberação

O pacote não deve ser publicado apenas porque compila. A CI precisa aprovar Android e iOS, a rota longa precisa passar pelo modelo de 25.000 pontos sem perda e o teste final deve ser feito com o carro parado no iPhone + ASUS K00E. Durante qualquer validação em movimento, a sinalização real deve ser obedecida mesmo que o aplicativo discorde.
