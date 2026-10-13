# Editor Foto

Editor de fotos para Android, **inspirado no Adobe Lightroom**. Sem login, sem anúncios e sem assinatura.

> **Aviso:** este é um projeto independente e não tem nenhuma ligação com a Adobe. "Adobe" e "Lightroom" são marcas registradas da Adobe Inc. O código foi escrito do zero (Kotlin + Jetpack Compose) e não usa nenhum código, arquivo ou recurso do aplicativo original. A ideia é apenas oferecer ferramentas de edição parecidas, de forma gratuita e offline.

## Recursos

- **Luz:** exposição, contraste, realces e sombras
- **Cor:** temperatura, matiz, vibração e saturação
- **HSL:** tom, saturação e luminância em 8 cores
- **Curva de tons:** 5 pontos arrastáveis
- **Presets:** 10 prontos, e você pode salvar os seus
- **Cortar:** proporções prontas, girar 90°, espelhar e endireitar
- **Comparar:** segure a foto para ver o original
- **Desfazer** e **Resetar**
- **Salvar** em JPEG (qualidade 95) em `Imagens/EditorFoto`

Tudo funciona offline e as fotos nunca saem do seu aparelho.

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

## Tecnologias

Kotlin, Jetpack Compose, Material 3 e processamento de imagem próprio (sem bibliotecas externas de edição).

## Licença

Defina uma licença no repositório (por exemplo, MIT) para deixar claro como o código pode ser usado.
