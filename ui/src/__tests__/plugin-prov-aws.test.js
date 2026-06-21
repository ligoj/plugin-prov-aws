import { describe, it, expect } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import pluginProvAwsDef from '../index.js'

describe('plugin-prov-aws contract', () => {
  it('exports required fields (id, label, install, feature, service, meta)', () => {
    expect(pluginProvAwsDef.id).toBe('prov-aws')
    expect(typeof pluginProvAwsDef.label).toBe('string')
    expect(typeof pluginProvAwsDef.install).toBe('function')
    expect(typeof pluginProvAwsDef.feature).toBe('function')
    expect(pluginProvAwsDef.service).toBeTypeOf('object')
    expect(pluginProvAwsDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('declares `requires: ["prov"]` — parent plugin must load first', () => {
    expect(pluginProvAwsDef.requires).toEqual(['prov'])
  })

  it('declares no routes — tool-level augmentation only', () => {
    expect(pluginProvAwsDef.routes).toBeUndefined()
  })

  it('feature() throws for unknown actions', () => {
    expect(() => pluginProvAwsDef.feature('unknown')).toThrow(/no feature "unknown"/)
  })

  it('renderFeatures() returns a console-link VNode when the account is set', () => {
    setActivePinia(createPinia())
    const result = pluginProvAwsDef.feature('renderFeatures', {
      id: 11,
      node: { id: 'service:prov:aws:foo' },
      parameters: { 'service:prov:aws:account': '123456789012' },
    })
    expect(Array.isArray(result)).toBe(true)
    expect(result.length).toBe(1)
    for (const node of result) expect(node.__v_isVNode).toBe(true)
    const href = result[0]?.props?.href
    expect(href).toBe('https://123456789012.signin.aws.amazon.com/console')
    expect(result[0]?.props?.target).toBe('_blank')
  })

  it('renderFeatures() returns an empty list when no account is set', () => {
    setActivePinia(createPinia())
    const result = pluginProvAwsDef.feature('renderFeatures', {
      id: 11,
      node: { id: 'service:prov:aws:foo' },
      parameters: {},
    })
    expect(result).toEqual([])
  })

  it('renderDetailsKey() returns a chip VNode for the AWS account', () => {
    setActivePinia(createPinia())
    const result = pluginProvAwsDef.feature('renderDetailsKey', {
      id: 11,
      node: { id: 'service:prov:aws:foo' },
      parameters: { 'service:prov:aws:account': '123456789012' },
    })
    expect(result).toBeTruthy()
    expect(result.__v_isVNode).toBe(true)
  })

  it('renderDetailsKey() returns null when no account is set', () => {
    setActivePinia(createPinia())
    const result = pluginProvAwsDef.feature('renderDetailsKey', {
      id: 11,
      node: { id: 'service:prov:aws:foo' },
      parameters: {},
    })
    expect(result).toBeNull()
  })

  it('dashboardLink() returns the CloudWatch dashboards URL', () => {
    expect(pluginProvAwsDef.feature('dashboardLink')).toBe(
      'https://console.aws.amazon.com/cloudwatch/home#dashboards:',
    )
  })
})
