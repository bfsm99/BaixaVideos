# BaixaVídeos (Android)

App Android que baixa vídeos do YouTube, Instagram, TikTok, X/Twitter e mais
de 1000 sites, usando o [yt-dlp](https://github.com/yt-dlp/yt-dlp) via
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android) + FFmpeg.

## Recursos
- Colar link ou **Compartilhar** direto do navegador/app do YouTube para o BaixaVídeos
- Prévia com miniatura, título, site e duração
- Qualidade: Melhor, 1080p, 720p, 480p ou só áudio (MP3)
- Progresso, cancelar, e botão para atualizar o yt-dlp quando algum site mudar
- Arquivos salvos em `Download/BaixaVideos`

## Como gerar o APK
**Android Studio:** File → Open → esta pasta → Build → Build APK(s).

**GitHub Actions (sem instalar nada):** suba a pasta para um repositório no GitHub; o workflow
`.github/workflows/build.yml` gera o APK e ele aparece em Actions → execução → Artifacts.

## Atualização automática
Cada push para `main` faz o GitHub compilar, assinar e publicar uma Release. Ao abrir, o app
procura uma Release mais recente e mostra o botão "Atualizar agora".

Requer os Secrets `SIGNING_KEY` e `SIGNING_PASSWORD` no repositório (conteúdo dos ficheiros em
`keystore/`, que nunca vai para o GitHub). Guarde uma cópia da pasta `keystore/`: sem ela não é
possível publicar atualizações que o telemóvel aceite.

Requer Android 8.0+. O APK tem ~70 MB por incluir Python, yt-dlp e FFmpeg.
