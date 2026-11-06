package cn.wayuwa.wao.controller;

/** Also applies to image aliases and favicons: uploaded SVGs can be opened as documents. */
final class ImageContentPolicy {
    static final String SANDBOX = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:";

    private ImageContentPolicy() {}
}
