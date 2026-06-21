import { renderServiceLink, renderDetailsChip, useI18nStore } from '@ligoj/host'

/** Pull the AWS account id out of a subscription. Mirrors the legacy
 *  `subscription.parameters['service:prov:aws:account']` lookup. */
function accountOf(subscription) {
  return subscription?.parameters?.['service:prov:aws:account'] ?? null
}

const service = {
  /**
   * Tool-level row actions for an AWS subscription. Appended to the
   * parent `plugin-prov`'s buttons via its delegation hook — mirrors the
   * legacy `current.$super(...)` inheritance where `aws.js` augmented
   * `prov.js#renderFeatures`.
   *
   * Renders a single anchor button opening the AWS sign-in console
   * scoped to this subscription's account id, in a new tab.
   */
  renderFeatures(subscription) {
    const { t } = useI18nStore()
    const account = accountOf(subscription)
    if (!account) return []
    return [renderServiceLink({
      icon: 'mdi-aws',
      href: `https://${encodeURIComponent(account)}.signin.aws.amazon.com/console`,
      title: t('service:prov:aws:console'),
    })]
  },

  /**
   * AWS account chip for the "Details" column. Mirrors the legacy
   * `renderKey` / `renderDetailsKeyCallback` carousel which exposed the
   * account id with the "Account" label.
   *
   * Returns null when no account parameter is set so the cell stays
   * empty rather than rendering an obviously-broken chip.
   */
  renderDetailsKey(subscription) {
    const { t } = useI18nStore()
    const account = accountOf(subscription)
    if (!account) return null
    return renderDetailsChip({ icon: 'mdi-aws', text: account, title: t('service:prov:aws:account'), size: 'x-small' })
  },

  /**
   * Returns the AWS CloudWatch dashboards URL. Kept as a feature for
   * parity with the legacy `dashboardLink(model)` callback the prov
   * terraform/dashboard tooling reaches for.
   */
  dashboardLink() {
    return 'https://console.aws.amazon.com/cloudwatch/home#dashboards:'
  },
}

export default service
