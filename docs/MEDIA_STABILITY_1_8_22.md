# C3 Media 1.8.22 — correção física de áudio e interface

## Evidência do teste físico

A 1.8.21 instalou, mas apresentou três regressões no ASUS K00E:

- espelhamento reduzido por módulos laterais;
- tema claro/escuro não aplicado;
- encerramento do processo ao iniciar a reprodução AirPlay.

## Causa do áudio

A 1.8.21 recompilou `libairplay_native.so` com um caminho de codec diferente do
binário K00E usado nas versões 1.8.12–1.8.14. O binário recompilado tinha SHA-256
`91ac36949f664f95c2992322b02424ded6e796c3504ea2b09aff1af4bd8f9d54` e podia
entrar no `MediaCodec` de áudio do Android 5.

A 1.8.22 restaura a pilha x86 já usada fisicamente no K00E. Ela usa codecs de
áudio por software e tem SHA-256
`327b381a2719aaa176f70a69d231e3c4671357c4cb0c87be74b99eb183c3e5b5`.
O Gradle e o CI recusam o build se qualquer uma das três bibliotecas mudar.
Para permitir o versionamento sem alterar os bytes, cada biblioteca é armazenada
em partes Base64. O Gradle reconstrói a pilha antes do build e confere novamente
os três SHA-256; os workflows também verificam os hashes dentro do APK pronto.

## Interface preservada

- espelhamento é a única camada funcional visível e ocupa toda a tela;
- `VideoLayoutCalculator` mantém proporção e gira fontes verticais sem esticar;
- player de música ocupa toda a tela, sem controles de faixa no tablet;
- capa é recortada como disco e gira somente enquanto `playing` é verdadeiro;
- título, artista, álbum, barra, tempo atual e duração permanecem;
- botão fechar apenas recolhe a interface e preserva o serviço/saída AirPlay;
- tema diurno (07:00–18:59) e noturno (19:00–06:59) volta a ser aplicado,
  inclusive à luminosidade ativa.

## Correção da bateria de testes

O gravador usa `files/last-crash.txt`, mas o script anterior verificava
`files/last_crash.txt`. A 1.8.22 corrige o nome e também abre a pilha nativa real
30 vezes, alternando ALAC, AAC-LC e AAC-ELD, antes dos ciclos de atividade e
rotação no emulador Android 5 x86.

O teste automatizado reduz o risco de regressão, mas o aceite final continua
dependendo do iPhone e do ASUS K00E reais.

## Diagnóstico persistente de encerramentos

A 1.8.22 mantém um diário limitado em disco com ciclo de vida, estado do
AirPlay, transições de áudio, espelhamento, metadados, chamadas nativas
relevantes e um heartbeat periódico com memória disponível. Se o processo
desaparecer sem passar por um encerramento normal, a abertura seguinte preserva
a sessão anterior em `last-crash.txt`.

Ao reabrir depois de uma falha, o app pergunta imediatamente onde o usuário
quer salvar o relatório `.txt` pelo seletor de arquivos do Android. A opção
"Depois" mantém o relatório no armazenamento interno e ele também permanece
visível em Ajustes técnicos → Último relatório de falha.

Esse mecanismo cobre exceções Kotlin/Java e também infere mortes nativas ou do
sistema a partir da sessão persistente. Reinicialização do tablet e atualização
do APK são diferenciadas para não gerar um alerta falso de falha.

## Compatibilidade JNI restaurada

O primeiro diagnóstico completo da pilha nativa restaurada apontou um aborto do
Android 5 durante `nativeInit`: o binário K00E procura diretamente o callback
público `AirPlayService.onAudioActivity()`. A 1.8.22 restaura esse método com a
assinatura exata e impede que ele seja removido ou renomeado em builds futuros.
