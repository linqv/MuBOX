MuPDF Android integration test fixtures
=======================================

These files are intentionally small Android test assets used by
`RealMuPdfDocumentAdapterInstrumentedTest` to prove the packaged MuPDF AAR can
open and render the document formats the app exposes.

- `sample.pdf`, `sample.epub`, `sample.mobi`
  - Source: github.com/gabriel-vasile/mimetype v1.4.3 testdata.
  - Upstream license: MIT.
  - SHA-256:
    - PDF: `e221259eb056a8454e31114cb2a352918ceb0cb31b07a78a8c013aa5571ddb02`
    - EPUB: `929c0b17e72dd4bcff385bd9947ca986e5a40b07cea70647cb70fb18c654e087`
    - MOBI: `6bd36290d71e0e6fe85b1902fba624368a70fa634fb52743027d5c34cf836f36`

- `sample.azw3`
  - Source: Standard Ebooks, Stanley G. Weinbaum, The Dark Other, AZW3 download.
  - URL:
    `https://standardebooks.org/ebooks/stanley-g-weinbaum/the-dark-other/downloads/stanley-g-weinbaum_the-dark-other.azw3?source=download`
  - The source page states the ebook is thought to be free of copyright
    restrictions in the United States.
  - SHA-256:
    `b4f110080c4b27c19aaad18f40429a16830908e960743bea059679e93ec3cee8`
