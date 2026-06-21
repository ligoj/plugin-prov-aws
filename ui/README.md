# plugin-prov-aws UI

Vue sources for the Ligoj "prov-aws" tool-level plugin. Augments `plugin-prov`
with AWS-specific parameter labels and subscription row actions (AWS console
link, account chip).

Built with Vite in library mode; the output bundle is placed under the
Java module's webjars classpath so the Ligoj host serves it at
`/main/prov-aws/vue/index.js`.

## Layout

```
ui/
├── package.json
├── vite.config.js            # library build → ../src/main/resources/.../webjars/prov-aws/vue/
├── index.html                # standalone dev entry
└── src/
    ├── index.js              # plugin contract entry (default export)
    ├── service.js            # feature implementations (renderFeatures, renderDetailsKey, dashboardLink)
    └── i18n/{en,fr}.js       # AWS-specific parameter labels
```

## Commands

```sh
npm install
npm run dev        # standalone dev server on :5176; proxies REST to :8080
npm run build      # writes ../src/main/resources/META-INF/resources/webjars/prov-aws/vue/index.js
```

## Delegation contract

`plugin-prov` walks the subscription node id (`service:prov:<tool>:...`) and
delegates `renderFeatures` / `renderDetailsKey` to the sub-plugin registered
as `prov-<tool>`. For AWS subscriptions (`service:prov:aws:*`) that lookup
resolves to this plugin, and the VNodes it returns get appended to the
parent's row actions / details column.

## Shared dependencies

`vue`, `vue-router`, `pinia`, and `vuetify` are kept **external** in the
build output — the host resolves them via an import map so the plugin
and host share the same module instances.
