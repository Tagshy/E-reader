# Leitura — Android

Leitor offline de PDFs para Android 8.0 ou superior. Importa documentos, mostra a primeira página como capa e salva o progresso e o tema.

## Instalação
Baixe Leitura.apk na versão mais recente em https://github.com/Tagshy/E-reader/releases/latest e abra no celular. Autorize a instalação para o navegador quando o Android solicitar.

## Compilação
O Actions compila assembleDebug e executa lintDebug com Java 17, Gradle 8.9 e Android SDK 35. O APK fica no artefato Leitura-APK por 90 dias e nas Releases após cada push na main.

Para compilar localmente, instale Java 17, Android SDK 35 e Gradle 8.9 e execute `gradle :app:assembleDebug :app:lintDebug`. O projeto original não contém Gradle Wrapper.

## Limitações
APK de debug para uso pessoal. Capas usam a primeira página do PDF. Animação de folha e zoom avançado ainda não estão implementados. PDFs protegidos ou removidos podem não abrir.
