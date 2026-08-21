# C3 Link 1.8.7 para iPhone

Companheiro da C3 Media 1.8.7, preservando a interface da versão 1.8.1. Calcula rotas automobilísticas pelo Apple MapKit, mantém a geometria integral no iPhone e no tablet e valida CRC32/contagem antes da confirmação. Radares e limites conhecidos do OpenStreetMap aparecem sem inventar dados ausentes. GPS em segundo plano, tiles 2D com contraste, confirmação e reenvio continuam ativos. Não captura a tela, não imita CarPlay e não contém voz de GPS.

## Build local no macOS

```bash
brew install xcodegen
cd ios/C3Link
xcodegen generate
xcodebuild \
  -project C3Link.xcodeproj \
  -scheme C3Link \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## Build pelo Windows

Use o workflow `.github/workflows/ios-c3-link.yml` no GitHub Actions. Ele testa e compila em um executor macOS e entrega `C3-Link-1.8.7-unsigned.ipa`. No Windows, assine e instale o arquivo com Sideloadly.

## Uso

1. Instale a C3 Media 1.8.7 no tablet.
2. Conecte o iPhone à rede `Citroen-C3` com IP manual e roteador vazio.
3. Abra o C3 Link, permita Rede local e Localização Sempre.
4. Procure um destino e escolha o resultado.
5. Confira a rota no mapa interativo, aguarde “Rota azul confirmada no tablet” e bloqueie o iPhone.
6. Para música, selecione **Citroën C3** no cartão de áudio AirPlay; não é necessário espelhar a tela.

Consulte `docs/INSTALLATION.md` e `docs/C3-LINK.md` na raiz do projeto.
