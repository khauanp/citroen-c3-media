# C3 Media 1.8.5 — correção do renderizador de rota

## Resultado do teste físico da 1.8.4

O iPhone confirmou e exibiu a rota completa, enquanto o tablet mostrou destino, tempo, distância e instrução. Isso confirma que o transporte e a decodificação da rota funcionaram. No K00E, porém, a linha continuou ausente e alguns quadros exibiram grandes blocos pretos.

A revisão do smali da 1.8.4 encontrou uma condição invertida no filtro de visibilidade: segmentos normais dentro da tela eram ignorados, enquanto alguns segmentos com coordenadas muito distantes podiam chegar ao `Canvas.drawLine`. A GPU do Android 5 transformava esses valores extremos nos blocos pretos observados.

## Correção 1.8.5

A 1.8.5 é reconstruída novamente sobre o APK real `C3-Media-1.8.1-K00E.apk`, SHA-256 `be86b35046e09ca900bd0acb30b47101e40a72e7ef0fc33f1a6085c26d9ccb3f`.

No método `DashboardView.drawNavigation()`:

- os dois pontos de cada segmento precisam estar dentro da margem segura `-256…1536` em X e `-256…1056` em Y;
- somente então o segmento recebe contorno escuro de 24 px e linha azul de 12 px;
- coordenadas muito distantes nunca são enviadas ao desenhador da GPU;
- o `Path` original continua presente como fallback;
- projeção, rotação, posição GPS, tiles e layout não mudam.

As melhorias de estabilidade de áudio, vídeo e permanência da rota da 1.8.4 são mantidas exatamente iguais. A verificação automática continua permitindo alterações somente em quatro métodos e exige igualdade de recursos, assets, manifesto e bibliotecas nativas com a 1.8.1.

Versão final: `1.8.5` (`versionCode 10805`). O aplicativo do iPhone permanece na 1.8.4 porque o teste físico confirmou que ele está enviando e exibindo a rota corretamente.

## Teste físico obrigatório

1. Instale a C3 Media 1.8.5 sobre a 1.8.4.
2. Abra a mesma rota e confirme que nenhum quadrado preto aparece.
3. Confirme a linha azul partindo da região do marcador atual.
4. Teste também uma rota curta.
5. Bloqueie o iPhone e confirme que marcador, instruções e linha continuam atualizados.

O teste automatizado prova o escopo da alteração, mas a confirmação visual final depende do ASUS K00E real.
