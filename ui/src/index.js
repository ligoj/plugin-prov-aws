/*
 * Plugin "prov-aws" — AWS implementation of plugin-prov.
 *
 * Tool-level plugin: lives at `service:prov:aws` in the node tree. It
 * does not own routes or a top-level component — it augments the parent
 * `plugin-prov` via:
 *
 *   - i18n: AWS-specific parameter labels and support-plan descriptions
 *     so the subscribe wizard's auto-rendered parameter form shows
 *     friendly names.
 *   - feature('renderFeatures', subscription): "Open AWS console" link
 *     button appended to the parent's row actions through plugin-prov's
 *     sub-plugin delegation hook.
 *   - feature('renderDetailsKey', subscription): AWS account chip in
 *     the details column.
 *   - feature('dashboardLink', subscription): returns the AWS CloudWatch
 *     dashboards URL, called from terraform/dashboard related views.
 *
 * Authored as source — compiled to `/main/prov-aws/vue/index.js` by Vite.
 * Shared host surface (stores, components) is imported from `@ligoj/host`
 * and kept external at build so plugin and host share the same instances.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
  renderDetailsKey: service.renderDetailsKey,
  dashboardLink: service.dashboardLink,
}

export default {
  id: 'prov-aws',
  label: 'AWS',
  // Declared dependency: the parent service-level plugin contributes the
  // inherited parameter labels, the delegation hook that pulls our
  // `renderFeatures` / `renderDetailsKey` VNodes into subscription rows,
  // and the `/prov/*` routes referenced from the host nav. The loader
  // awaits these before calling our install(), so by the time we merge
  // our AWS-specific i18n the parent's bundle is already in the store —
  // labels resolve correctly on the first render.
  requires: ['prov'],
  // No routes — AWS-specific screens (the legacy `aws.html` was empty)
  // and parameter forms come from the parent's wizard.
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "prov-aws" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-aws', color: 'orange-darken-3' },
}

export { service }
