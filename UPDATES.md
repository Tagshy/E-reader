# Atualizações do Leitura

## O que muda
O app terá o botão Atualizações. Quando houver uma release mais recente, o botão muda para Atualizar. O usuário baixa o APK, o app confere SHA-256, identidade, versão e certificado, e abre a confirmação de instalação do Android. Nenhuma instalação é silenciosa. Sem internet, os PDFs continuam funcionando.

O Actions atribui um versionCode crescente automaticamente a cada publicação. Alterações comuns podem ser enviadas ao mesmo repositório; após testes, uma nova release é criada sem apagar a anterior.

## Configuração única de assinatura
A integração do GitHub usada pelo ChatGPT não tem acesso a Secrets. Uma pessoa autenticada precisa cadastrá-los uma vez.

A chave privada NUNCA deve ser enviada aos arquivos do repositório, releases, logs ou artefatos.

No Windows, com JDK (ou Android Studio) e [GitHub CLI oficial](https://cli.github.com) instalados:
1. Execute `gh auth login` e autentique sua própria conta.
2. Na pasta do projeto desta branch, execute `powershell -File .\scripts\configure-signing.ps1`.
3. O script cria a chave fora do repositório, mantém a senha local protegida pela conta do Windows e cadastra os três Secrets privados abaixo. Se a chave já existir, ele a reutiliza; não a substitui.
4. Preserve a chave e mantenha uma cópia recuperável de sua senha em um gerenciador seguro. O arquivo DPAPI depende da mesma conta Windows e não basta para recuperação em outro computador.
5. Depois de configurar os Secrets e aprovar os testes, promova esta branch para main. O Actions publicará a primeira versão assinada com a chave definitiva.

Secrets em Settings → Secrets and variables → Actions:
- ANDROID_KEYSTORE_BASE64: keystore completo codificado em base64.
- ANDROID_KEYSTORE_PASSWORD: senha da chave (mesma senha para keystore e alias).
- ANDROID_SIGNING_CERT_SHA256: impressão digital SHA-256 do certificado, sem separadores.
O alias fixo é leitura.

A publicação falha se os Secrets estiverem ausentes ou se o certificado não corresponder ao esperado. Não existe fallback para assinatura temporária.

## Transição da versão 2.0
O APK 2.0 publicado anteriormente foi assinado com uma chave temporária do runner, que não foi preservada. Não é possível assinar uma atualização compatível com esse APK sem a chave original. A primeira migração para a assinatura definitiva pode exigir reinstalação; guarde os PDFs originais e anote favoritos/páginas antes de qualquer desinstalação.

Depois de instalada a primeira versão definitiva, as futuras atualizações com a mesma assinatura preservam os dados do aplicativo. Não altere applicationId, não troque a chave e não reduza o versionCode.

## Verificações
O CI compila debug, testes e o código de release, executa lint e testa leitura offline, migração, busca, favoritos e atualizador. No emulador, também instala um APK de versão 4 por cima de um APK de versão 3 assinado com a mesma chave de teste e confere que preferências e arquivos foram preservados.

A validação do instalador de produção e a primeira release assinada dependem do cadastro dos Secrets; o CI de debug não é uma confirmação de que a chave definitiva já foi configurada.
