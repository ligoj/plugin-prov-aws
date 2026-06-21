// Plugin-local translations merged into the host i18n store at install
// time. Keep keys flat (colon-separated for parameter ids) to match the
// host's convention — the wizard's `t(p.id)` reads them as-is.
//
// Most of these keys are AWS-specific parameter labels — the parameter
// inputs are auto-rendered by the subscribe wizard from the backend's
// metadata; supplying labels here is what turns the raw parameter ids
// like `service:prov:aws:account` into user-friendly form labels.
export default {
  // Subscription row actions contributed via renderFeatures.
  'service:prov:aws:console': 'AWS Console',

  // AWS API credentials (subscribe wizard parameter labels).
  'service:prov:aws:access-key-id': 'Access Key Id',
  'service:prov:aws:secret-access-key': 'Secret Access Key',
  'service:prov:aws:account': 'Account ID',

  // AWS Support plan descriptions. Plain text — vue-i18n's flat resolver
  // returns the message verbatim, so the host renders it as text rather
  // than HTML. Bullet markers are preserved with `•`.
  'service:prov:aws:support:developer':
    'The Developer Support plan offers resources for customers testing or doing early development on AWS, as well as any customers who: • Want access to guidance and technical support; • Are exploring how to quickly put AWS to work; • Use AWS for non-production workloads or applications.',
  'service:prov:aws:support:business':
    'The Business Support plan offers resources for customers running production workloads on AWS as well as any customers who: • Run one or more applications in production environments; • Have multiple services activated, or use key services extensively; • Depend on their business solutions to be available, scalable, and secure.',
  'service:prov:aws:support:enterprise':
    'The Enterprise Support plan offers resources for customers running business & mission critical workloads on AWS, as well as any customers who want to: • Focus on proactive management to increase efficiency and availability; • Build and operate workloads following AWS best practices; • Leverage AWS expertise to support launches and migrations.',
}
