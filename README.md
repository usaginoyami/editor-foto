# Editor Foto

Editor de fotos para Android, **inspirado no Adobe Lightroom**. Sem login, sem anúncios e sem assinatura.

> **Aviso:** este é um projeto independente e não tem nenhuma ligação com a Adobe. "Adobe" e "Lightroom" são marcas registradas da Adobe Inc. O código foi escrito do zero (Kotlin + Jetpack Compose) e não usa nenhum código, arquivo ou recurso do aplicativo original. A ideia é apenas oferecer ferramentas de edição parecidas, de forma gratuita e offline.

## Recursos

- **Luz:** exposição, contraste, realces e sombras
- **Cor:** temperatura, matiz, vibração e saturação
- **HSL:** tom, saturação e luminância em 8 cores
- **Curva de tons:** 5 pontos arrastáveis
- **Presets:** 10 prontos, e você pode salvar os seus
- **Efeitos:** nitidez, vinheta e granulação
- **Cortar:** corte manual arrastando cantos e bordas (livre ou 1:1, 4:5, 3:2, 16:9, original), girar 90°, espelhar e endireitar
- **Histograma** ao vivo (botão HIST na prévia) e **zoom** com pinça (toque duplo volta ao normal)
- **Comparar:** segure a foto, sem mexer, para ver o original
- **Compartilhar** a foto salva (botão COMPARTILHAR na prévia) e **abrir fotos de outros apps** ("Abrir com", "Editar" e o menu Compartilhar)
- **Desfazer** e **Resetar**
- **Salvar** em JPEG (qualidade 95) em `Imagens/EditorFoto`, na **resolução original** da foto e mantendo data, câmera e exposição (EXIF)
- **P&B de verdade:** saturação em -100 usa a luma, então as cores viram tons de cinza distintos
- **Exposição e temperatura em luz linear**, para um resultado mais natural

Tudo funciona offline e as fotos nunca saem do seu aparelho.

## Baixar

Os APKs ficam na aba [Releases](https://github.com/usaginoyami/editor-foto/releases). Para instalar, permita "instalar apps de fontes desconhecidas" no Android.

## O que ele não tem

Recursos que dependem de IA ou da nuvem do Lightroom original, como máscaras inteligentes, remover objetos, sincronização e edição de RAW.

## Como gerar o APK

1. Instale o [Android Studio](https://developer.android.com/studio).
2. Clone o repositório e abra a pasta em **File > Open**.
3. Espere o Gradle sincronizar (use um JDK entre 17 e 22 em **Settings > Build Tools > Gradle > Gradle JDK**).
4. Para testar, clique em **Run** com o celular conectado ou um emulador.
5. Para gerar o APK, vá em **Build > Generate App Bundles or APKs > Generate APKs**.

O APK de teste fica em `app/build/outputs/apk/debug/app-debug.apk`.

**Requisitos:** Android 10 ou mais novo (minSdk 29).

## Publicar uma release (para quem mantém o projeto)

1. Gere uma chave uma única vez: `keytool -genkey -v -keystore release.jks -alias editorfoto -keyalg RSA -keysize 2048 -validity 10000`
2. Para assinar localmente, crie `keystore.properties` na raiz (já está no `.gitignore`):
   ```
   storeFile=release.jks
   storePassword=...
   keyAlias=editorfoto
   keyPassword=...
   ```
   e rode `gradle assembleRelease` (ou use **Build > Generate Signed Bundle / APK** no Android Studio).
3. Para o GitHub Actions assinar sozinho, cadastre em **Settings > Secrets and variables > Actions** os secrets `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.
4. Crie a tag e envie: `git tag v1.0 && git push origin v1.0`. O workflow gera o APK e abre a release. Sem os secrets, ele publica um APK de debug.

Guarde o `release.jks` em local seguro: sem ele não dá para atualizar o app para quem já instalou.

## Tecnologias

Kotlin, Jetpack Compose, Material 3 e processamento de imagem próprio (sem bibliotecas externas de edição).

## Licença

[MIT](LICENSE). Você pode usar, modificar e distribuir, mantendo o aviso de copyright.
