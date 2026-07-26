import assert from 'node:assert/strict';

// Test the label normalization logic used in useLabelRotate
// The pattern: typeof label === 'object' ? (label.formattedLabel || label.rawLabel) : String(label)

function normalizeLabel(label: unknown): string {
  if (typeof label === 'object' && label !== null) {
    const obj = label as { formattedLabel?: string; rawLabel?: string };
    return obj.formattedLabel || obj.rawLabel || '';
  }
  return String(label);
}

// String label
assert.equal(normalizeLabel('hello'), 'hello');

// Number label (chart data contract allows number[])
assert.equal(normalizeLabel(42), '42');
assert.equal(normalizeLabel(0), '0');
assert.equal(normalizeLabel(-1.5), '-1.5');

// Object label (ECharts formatted label object)
assert.equal(normalizeLabel({ formattedLabel: 'fmt', rawLabel: 'raw' }), 'fmt');
assert.equal(normalizeLabel({ rawLabel: 'raw' }), 'raw');
assert.equal(normalizeLabel({}), '');

// Null/undefined
assert.equal(normalizeLabel(null), 'null');
assert.equal(normalizeLabel(undefined), 'undefined');

console.log('useLabelRotate label normalization tests passed');
