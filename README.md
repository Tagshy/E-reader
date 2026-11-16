# Leitura 2.0 — Android
Leitor pessoal offline de PDFs para Android 8.0 ou superior, com tema escuro permanente e identidade dourada aprovada.

## Recursos
- Biblioteca em grade com capa real da primeira página, busca por título, favoritos e recentes.
- Importação múltipla com nome original e deduplicação por conteúdo.
- Cópias privadas dos PDFs: leitura sem internet mesmo após apagar ou mover o original.
- Retomada da página, progresso percentual, gestos laterais, botões e seletor de páginas.
- GIF original na abertura, botão para pular e logo no app e no ícone.
- Migração da biblioteca 1.0 e de suas chaves de progresso; fontes indisponíveis não apagam os registros.

## Instalação
[Releases e APKs](https://github.com/Tagshy/E-reader/releases).
As releases anteriores são preservadas. Os APKs de debug do Actions usam chaves temporárias, portanto o Android pode recusar atualização por assinatura diferente. Não desinstale a versão anterior sem guardar seus PDFs e anotar o progresso. Para atualizações compatíveis entre builds, configure futuramente uma chave privada em GitHub Secrets; nenhuma chave privada é publicada neste repositório.

## Validação e publicação
A branch leitura-2.0 valida a mudança antes da promoção para main. O Actions usa Java 17, Gradle 8.9 e Android SDK 35; executa assembleDebug, testDebugUnitTest, lintDebug e connectedDebugAndroidTest em Android 10. A publicação cria uma nova tag exclusiva e só ocorre na main depois de todos os passos passarem. Inclui SHA-256 do APK.

Os testes verificam busca/favoritos/recentes, limites do progresso, renderização após excluir o PDF original, persistência e migração de páginas legadas, rejeição de PDF inválido e navegação da interface.

Localmente, instale Java 17, Gradle 8.9 e SDK 35:
```sh
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# Com um emulador/dispositivo Android conectado:
gradle :app:connectedDebugAndroidTest
```
O projeto de origem não possui Gradle Wrapper.

## Limitações
PDFs protegidos por senha não são suportados. O documento mantém suas cores originais para preservar o conteúdo; a interface permanece escura. A animação de troca é uma transição suave, sem efeito de folha curva. Remover um livro exige confirmação e não apaga o arquivo original. As cópias privadas ocupam armazenamento e são apagadas ao desinstalar o app.

## Atualizações com assinatura fixa
A preparação da versão 2.1 está documentada em [UPDATES.md](UPDATES.md). É necessário cadastrar os Secrets privados antes da primeira publicação desta versão. O APK de debug não é mais publicado automaticamente.
