# Instalação no ASUS K00E

## 1. Instalar o APK

1. Copie `C3-Media-1.2.0-K00E.apk` para o tablet.
2. Em **Configurações → Segurança**, permita a instalação de fontes desconhecidas.
3. Abra o APK e conclua a instalação.
4. Inicie **C3 Media**.
5. Quando o Android perguntar qual tela inicial usar, escolha **C3 Media** e marque **Sempre**.

O aplicativo abre em paisagem, oculta as barras do Android e volta a iniciar após o boot.

## 2. Ligar o tablet ao rádio

1. Na tela principal, mantenha o logotipo Citroën pressionado por aproximadamente 2 segundos.
2. Digite o PIN técnico `0303`.
3. Abra **Bluetooth do rádio**.
4. Pareie o K00E com o rádio e selecione a saída de áudio Bluetooth.

## 3. Ligar o iPhone ao tablet

O aplicativo tenta criar a rede abaixo:

- Rede: `Citroen-C3`
- Senha: `C3Media26`

Conecte o iPhone a essa rede. Se o firmware não permitir criar o ponto de acesso automaticamente, abra o menu técnico e configure o ponto de acesso do tablet manualmente. Outra opção é colocar iPhone e tablet na mesma rede Wi-Fi.

### Manter os dados móveis do iPhone

O iOS normalmente tenta usar a rede Wi-Fi como rota de internet. Como `Citroen-C3` é uma rede local, configure-a uma vez para não substituir a rota dos dados móveis:

1. No iPhone, abra **Ajustes → Wi-Fi → ⓘ** ao lado de `Citroen-C3`.
2. Em **Configurar IP**, escolha **Manual**.
3. Use IP `192.168.43.2` e máscara `255.255.255.0`.
4. Deixe **Roteador** vazio e mantenha **Configurar DNS** em **Automático**.
5. Volte à tela anterior e teste o YouTube Music usando 4G/5G. Se algum aplicativo não resolver endereços, configure o DNS manual `1.1.1.1`.

Se o endereço do tablet não for `192.168.43.1`, mantenha o logotipo Citroën pressionado, digite `0303` e abra **Internet móvel no iPhone**. A C3 Media mostrará o IP correto para este firmware.

## 4. YouTube Music ou Spotify

1. Abra o aplicativo de música no iPhone e inicie uma faixa.
2. Abra a Central de Controle do iPhone.
3. Toque no ícone de saída AirPlay do cartão de música.
4. Selecione **Citroën C3**.

O som passa pelo tablet e sai no rádio. Quando fornecidos pelo iPhone, capa, título e artista aparecem na C3 Media. Os botões do tablet enviam play, pause, anterior e próxima faixa de volta ao iPhone.

## 5. Waze

1. Defina a rota normalmente no Waze do iPhone.
2. Abra a Central de Controle.
3. Toque em **Espelhar a Tela**.
4. Selecione **Citroën C3**.

A tela do Waze aparece na central. Se o iPhone transmitir em formato vertical, a C3 Media gira a imagem automaticamente, preserva a proporção original e usa barras pretas quando necessário para não distorcer. O áudio também segue pelo tablet ao rádio. O controle da rota continua no iPhone.

Na versão 1.2, o Waze ocupa o módulo grande à esquerda e a música fica em um módulo lateral com capa, progresso e botões. Ao trocar do Waze para YouTube/YouTube Music, o decodificador anterior é encerrado antes da nova sessão para proteger a memória do K00E.

O espelhamento AirPlay é unidirecional: ele transporta imagem e áudio, mas não envia o toque do tablet ao iPhone. Os botões de anterior, play/pause e próxima faixa da C3 Media são tocáveis; o Waze continua sendo operado no iPhone.

Sem dongle CarPlay, mantenha o Waze visível e o iPhone acordado para conservar o mapa ao vivo. Ao colocar o Waze em segundo plano ou bloquear o iPhone, o iOS deixa de transmitir a imagem do mapa; isso não pode ser contornado por um receptor AirPlay.

## 6. Espera, temperatura e alimentação contínua

- Após 45 segundos sem o iPhone na rede, a C3 Media encerra vídeo, áudio e capa e deixa a tela praticamente preta.
- Quando o iPhone volta à rede, a tela desperta e a animação Citroën é mostrada.
- A partir de 43 °C, o brilho é reduzido e novas capas são ignoradas até o resfriamento.
- No menu técnico, abra **Energia e temperatura** para ver carga, temperatura e memória livre.
- **Última falha registrada** mostra o diagnóstico de falhas Java; a central tenta se abrir novamente sozinha.

O Android 5 do K00E não oferece ao aplicativo um controle seguro do limite físico de carga. Use fonte regulada, proteção contra transientes e a ventilação planejada. Não remova a bateria nem faça jumper nos terminais.

## 7. Saída do modo central

A navegação comum fica bloqueada de propósito. Para alterar Wi-Fi ou Bluetooth, mantenha o logotipo pressionado e use o PIN `0303`. Em instalação fixa, o modo de proprietário do dispositivo pode tornar o bloqueio mais rígido, mas isso é opcional e exige configuração por ADB em um Android sem contas configuradas.

## Primeiro teste recomendado

Faça o primeiro teste fora do carro e com a bateria supervisionada:

1. confirme que o APK abre;
2. confirme que **Citroën C3** aparece no AirPlay do iPhone;
3. teste 15 minutos de YouTube Music;
4. teste capa e comandos;
5. teste o espelhamento com o Waze;
6. pareie o tablet ao rádio e confirme o áudio.

Não faça jumper nos contatos da bateria. A alimentação automotiva definitiva deve usar um eliminador de bateria regulado e proteção contra transientes dos 12 V.
