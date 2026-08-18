# Continuidade do projeto

Atualizado em: 18 de agosto de 2026 — versão 1.2.0

## Objetivo fechado

Criar uma única versão utilizável da C3 Media para o ASUS K00E do usuário: Android 5.0/API 21, Intel x86/i386, firmware `LRX21V.WW_epad-V7.6.0-20151125`, kernel `3.10.20-i386_ctp`, tela 1280×800.

Requisitos do usuário:

- YouTube Music obrigatório;
- música escolhida e controlada no iPhone;
- tablet mostra metadados e envia o áudio ao rádio por Bluetooth;
- Waze configurado no iPhone aparece no tablet;
- splash Citroën, abertura automática e interface sem saída comum;
- visual automotivo modular inspirado na organização do CarPlay, sem fingir integração CarPlay/MFi;
- sem dongle ou módulo CarPlay.

## Decisão de arquitetura

O Android 5 não executa o YouTube Music atual, e o K00E não pode funcionar como ponte Bluetooth iPhone → tablet → rádio usando apenas perfis padrão. A solução implementada usa AirPlay:

1. iPhone executa YouTube Music/Spotify/Waze.
2. C3 Media recebe áudio ou espelhamento pela rede local.
3. Android encaminha o fluxo de mídia para o rádio A2DP pareado.
4. RAOP/DMAP fornece, quando enviado pelo iOS, título, artista, capa e progresso.
5. DACP envia comandos de reprodução ao iPhone.
6. O espelhamento apresenta a rota do Waze sem depender do Waze antigo no tablet.

## Base técnica

- Repositório-base: `jqssun/android-airplay-server`.
- Commit-base local: `1fa1d60`.
- UxPlay submodule: `21eef8df25d91e12635c36d8176ad192725baca2`.
- Aplicativo: `com.c3media.dashboard`.
- Namespace das classes: `io.github.jqssun.airplay`.
- `minSdk 21`, `targetSdk 28`, ABI única `x86`.
- Receptor nativo: UxPlay + JNI + OpenSSL + FFmpeg + libplist + Oboe/OpenSL ES.
- Interface desenhada diretamente em `Canvas` para reduzir memória, dependências e custo de renderização no hardware de 2013.

## Implementado

- `app/src/lite`: variante leve compatível com API 21.
- `AirPlayService`: servidor, foreground service, wake lock, áudio, vídeo, estado e metadados.
- `NsdServiceManager`: anúncios `_raop._tcp` e `_airplay._tcp` por Android NSD.
- `AudioRenderer`: integração com o motor nativo/Oboe.
- `VideoRenderer` + `VideoPipeline`: H.264 com `MediaCodec` e EGL/OpenGL ES 2.
- `DmapParser` e `TrackInfo`: metadados e capa.
- `DacpController`: anterior, play/pause e próxima.
- `HotspotController`: tentativa de ativar `Citroen-C3`, fallback manual e detecção leve de cliente pela tabela ARP.
- `EnergyController`/`EnergyPolicy`: espera após 45 s, monitor de bateria/temperatura/memória e proteção térmica a 43 °C.
- `DashboardView`: splash, espera, home, música, conexão, erro, PIN e painel modular Waze + mídia.
- `C3MediaApplication`: registro da última falha Java e relançamento do painel.
- `BootReceiver`: serviço e tela após o boot.
- launcher HOME, tela cheia e opção de lock task/device owner.

## Correções críticas já feitas

1. Bibliotecas nativas compiladas apenas para x86/API 21.
2. OpenSSL ligado estaticamente dentro de `libairplay_native.so` para evitar símbolos ausentes no Android 5.
3. `-Wl,-Bsymbolic` e exclusão de bibliotecas estáticas para impedir resolução acidental contra a libcrypto antiga do sistema.
4. Callback de log nativo deixou de procurar incorretamente um método Kotlin estático.
5. Manifesto, permissões e APIs foram reduzidos para o Android 5.
6. H.265 desativado; o alvo usa H.264 para compatibilidade com o decodificador Intel.
7. Limite de arte em 384 px/RGB_565 e interface sem Compose/Media3 para preservar os 1 GB de RAM.

## Retorno do teste físico e correções 1.1.0

O teste real confirmou instalação, inicialização, descoberta AirPlay e espelhamento, e revelou cinco ajustes:

1. A rede local sem internet assumia a rota padrão do iPhone. O procedimento agora usa IP manual sem gateway na rede `Citroen-C3`, preservando o 4G/5G para Waze e streaming.
2. A interface ganhou áreas de toque maiores, resposta tátil, cartões de ajuda e controles completos sobre o mapa. O toque sobre o conteúdo do Waze continua impossível por limitação do AirPlay, não do digitalizador do K00E.
3. Metadados/progresso do YouTube Music não alteram mais `MIRROR` para `AUDIO`. Capas usam RGB_565, limite de 384 px e proteção contra falta de memória.
4. Fontes pequenas receberam escala de legibilidade automotiva entre 12% e 20%.
5. `VideoPipeline` agora detecta retrato, gira 90° e calcula `aspect fit` pela resolução real de qualquer emissor. Mudanças de orientação reiniciam o `MediaCodec` no próximo keyframe.

## Retorno do segundo teste físico e correções 1.2.0

O segundo teste confirmou rede, toque e controles, mas a troca do Waze para YouTube/YouTube Music encerrava a central inteira. A correção e a evolução de UX ficaram assim:

1. O callback nativo `onVideoStop` antes era vazio. Agora ele encerra o `MediaCodec`, zera a resolução da sessão e conserva apenas a pipeline reutilizável. `onAudioOnly` e o fim da conexão fazem a mesma limpeza, evitando que o codec Intel antigo e a capa concorram pela memória.
2. O espelhamento usa um `SurfaceView` restrito ao card de mapa (778×672 no design 1280×800). `VideoPipeline` continua aplicando rotação e `aspect fit`; a imagem não é esticada.
3. O card lateral de mídia oferece capa, faixa, artista, progresso e botões grandes enquanto o mapa está visível.
4. Sem cliente por 45 segundos, a central entra em `STANDBY`: descarta codec, áudio e bitmap, reduz o desenho da UI e coloca o brilho em 1%. O receptor mínimo continua vivo para não perder a reconexão.
5. O retorno do iPhone detectado por conexão AirPlay ou `/proc/net/arp` desperta a Activity e executa novamente a animação Citroën.
6. A temperatura de bateria a partir de 43 °C ativa redução de brilho e bloqueia novas capas; memória livre abaixo de 96 MB também bloqueia capas.
7. Falhas Java são gravadas em `files/last-crash.txt`, a Activity é reagendada via `AlarmManager` e o menu técnico permite ler o último diagnóstico. `onTaskRemoved` também agenda o retorno do launcher.

Limite aceito pelo usuário (opção A, sem dongle): AirPlay não fornece uma sessão independente do Waze. Se o app for ao segundo plano ou a tela do iPhone for bloqueada, o mapa deixa de ser transmitido. O painel lateral de mídia funciona com os dados que o iOS enviar, mas não transforma o receptor em CarPlay.

## Validações concluídas

- `clean`, `testDebugUnitTest`, `assembleDebug` e `lintDebug`: aprovados em build limpo.
- Testes unitários DMAP: metadados aninhados, UTF-8 e entrada malformada.
- APK contém `lib/x86/libairplay_native.so`, `libc++_shared.so` e `liboboe.so`.
- Instalação e abertura em emulador Android 5/API 21 x86 já chegaram à tela “Central conectada”.
- Dois problemas anteriores foram reproduzidos e corrigidos: símbolo OpenSSL ausente e callback JNI `onLog` com assinatura errada.
- Versão 1.1.0: testes unitários de rotação/aspect ratio, build limpo, lint, APK debug/release e teste visual/tátil no emulador API 21 aprovados.
- Atualização assinada 1.0.0 → 1.1.0 instalada com sucesso no emulador, preservando pacote e ABI x86.
- Versão 1.2.0: `clean`, testes unitários (incluindo política de energia), lint, APK debug e APK release x86 assinado aprovados; teste físico da transição real ainda obrigatório.

## Assinatura do APK

- O APK entregue é uma build `release`, sem flag de depuração.
- Certificado SHA-256: `02e9c5d7abfc2a91fe51fc76eac3ef791aa5c06948a00108a6c7c16a839a94ff`.
- A chave privada não deve ser enviada ao GitHub. Ela está no backup privado `C3-Media-signing-backup.zip` e deve ser reutilizada para que futuras versões instalem como atualização sobre a 1.0.0/1.1.0/1.2.0.

## Testes físicos ainda obrigatórios

Não declarar a versão final de hardware sem estes testes no K00E real:

1. instalar a atualização 1.2.0 sobre a 1.1.0;
2. confirmar dados móveis com IP manual sem gateway;
3. espelhar Waze, trocar para YouTube Music e YouTube repetidamente e confirmar que a central não encerra;
4. confirmar áudio no alto-falante do tablet;
5. parear o rádio e confirmar roteamento A2DP;
6. conferir capa/metadados, DACP e novos alvos de toque;
7. espelhar o Waze e validar rotação, proporção, fluidez e temperatura;
8. testar mudança de orientação durante o espelhamento;
9. desconectar o iPhone, aguardar 45 s, validar tela quase preta e reconectar para validar despertar/splash;
10. reiniciar o K00E e validar boot/launcher.

## Limitações que não são bugs

- Não existe CarPlay/MFi no APK.
- O primeiro pareamento/seleção AirPlay é feito no iPhone; o iOS pode sugerir reconexão, mas o APK não pode forçar o espelhamento.
- O toque do K00E não controla o Waze espelhado.
- Sem dongle CarPlay, Waze em segundo plano ou iPhone bloqueado não fornece mapa ao vivo ao AirPlay.
- Device owner não fornece controle do circuito de carga. A C3 Media reduz consumo e calor, mas não limita eletricamente a bateria.
- Conteúdo de vídeo protegido por DRM não é objetivo.
- O APK é específico para x86/Android 5 e não atende às exigências atuais da Play Store.

## Próximo passo exato

Gerar/instalar a 1.2.0 no K00E e repetir o roteiro físico acima. Se houver encerramento, abrir **Ajustes técnicos → Última falha registrada** e, quando possível, coletar `adb logcat` filtrando `C3Media`, `AirPlayNative`, `VideoPipeline`, `MediaCodec` e `AndroidRuntime`.
