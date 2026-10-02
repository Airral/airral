// libs/shared-utils/src/lib/formatters.spec.ts
//
// Run with `nx test shared-utils`. Node's own test runner, so the rules below
// need no browser and no test framework in the dependency tree.
import { describe, it } from 'node:test';
import * as assert from 'node:assert/strict';
import { cleanLocationLabel, formatPayLabel, hasPostedPay, isImplausibleHourlyPay } from './formatters';

describe('formatPayLabel', () => {
  it('adds /hr to an hourly figure whose label has no unit', () => {
    assert.equal(formatPayLabel('USD $25', 'HOUR'), '$25/hr');
    assert.equal(formatPayLabel('USD $20-$33', 'HOUR'), '$20–$33/hr');
  });

  it('does not double a unit the label already states', () => {
    assert.equal(formatPayLabel('USD $28-$38/hr', 'HOUR'), '$28–$38/hr');
    assert.equal(formatPayLabel('USD $62/hr', 'HOUR'), '$62/hr');
    assert.equal(formatPayLabel('$40 per hour', 'HOUR'), '$40 per hour');
    assert.equal(formatPayLabel('$40 an hour', 'HOUR'), '$40 an hour');
    assert.equal(formatPayLabel('$40 hourly', 'HOUR'), '$40 hourly');
  });

  it('adds nothing when the API stated no interval', () => {
    // Rocket Lab's intern rate, as the list API sends it today.
    assert.equal(formatPayLabel('USD $25', null), '$25');
    assert.equal(formatPayLabel('USD $25', undefined), '$25');
    assert.equal(formatPayLabel('USD $25', ''), '$25');
    assert.equal(formatPayLabel('USD $25', 'FORTNIGHT'), '$25');
  });

  it('leaves a yearly figure as the API writes it', () => {
    assert.equal(formatPayLabel('USD $122k-$168k', 'YEAR'), '$122k–$168k');
  });

  it('says the other intervals the same way the API does', () => {
    assert.equal(formatPayLabel('USD $4k', 'MONTH'), '$4k/mo');
    assert.equal(formatPayLabel('USD $900', 'WEEK'), '$900/wk');
    assert.equal(formatPayLabel('USD $200', 'DAY'), '$200/day');
    assert.equal(formatPayLabel('USD $10k', 'ONE_TIME'), '$10k total');
    assert.equal(formatPayLabel('USD $25', 'hour'), '$25/hr');
  });

  it('writes cents with two decimals and keeps them before the unit', () => {
    assert.equal(formatPayLabel('USD $21.1-$31.64', 'HOUR'), '$21.10–$31.64/hr');
    assert.equal(formatPayLabel('USD $22.5-$25.08/hr', 'HOUR'), '$22.50–$25.08/hr');
  });

  it('keeps a non-US currency code and only swaps the dash', () => {
    assert.equal(formatPayLabel('CAD $36k-$50k', 'YEAR'), 'CAD $36k–$50k');
    assert.equal(formatPayLabel('EUR 30-40', 'HOUR'), 'EUR 30–40/hr');
  });

  it('returns nothing for pay that was not posted', () => {
    assert.equal(formatPayLabel('Salary not listed', 'HOUR'), '');
    assert.equal(formatPayLabel('USD $0k-$0k', 'YEAR'), '');
    assert.equal(formatPayLabel('', 'HOUR'), '');
    assert.equal(formatPayLabel(null), '');
    assert.equal(formatPayLabel('  ', 'HOUR'), '');
  });
});

describe('hasPostedPay', () => {
  it('accepts a real figure', () => {
    assert.equal(hasPostedPay('USD $25'), true);
    assert.equal(hasPostedPay('USD $10k'), true);
  });

  it('rejects the placeholders the feed sends', () => {
    assert.equal(hasPostedPay('Salary not listed'), false);
    assert.equal(hasPostedPay('Benchmark needed'), false);
    assert.equal(hasPostedPay('N/A'), false);
    assert.equal(hasPostedPay('USD $0k'), false);
    assert.equal(hasPostedPay(undefined), false);
  });
});

describe('hourly pay under the minimum wage', () => {
  it('is not treated as posted pay', () => {
    // The Target truck-driver posting: a per-mile rate or bonus in the pay field.
    assert.equal(isImplausibleHourlyPay('USD $2-$3.5/hr'), true);
    assert.equal(hasPostedPay('USD $2-$3.5/hr'), false);
    assert.equal(formatPayLabel('USD $2-$3.5/hr', 'HOUR'), '');
    assert.equal(hasPostedPay('$6.50 per hour'), false);
  });

  it('leaves real hourly pay, and the minimum itself, alone', () => {
    assert.equal(hasPostedPay('USD $7.25/hr'), true);
    assert.equal(hasPostedPay('USD $20.82-$37.45/hr'), true);
    assert.equal(formatPayLabel('USD $20.82-$37.45/hr', 'HOUR'), '$20.82–$37.45/hr');
  });

  it('leaves a tipped cash wage alone', () => {
    assert.equal(hasPostedPay('$2.13/hr + tips'), true);
  });

  it('never applies to a yearly figure, however small the digits', () => {
    assert.equal(hasPostedPay('USD $5k'), true);
    assert.equal(hasPostedPay('USD $5k-$6k/yr'), true);
    assert.equal(isImplausibleHourlyPay('USD $150k-$190k'), false);
    assert.equal(isImplausibleHourlyPay(undefined), false);
  });
});

describe('cleanLocationLabel', () => {
  it('drops the empty brackets store postings arrive with', () => {
    assert.equal(cleanLocationLabel('Park Meadows Mall, Lone Tree, CO ( )'), 'Park Meadows Mall, Lone Tree, CO');
    assert.equal(cleanLocationLabel('SoHo, New York, NY ()'), 'SoHo, New York, NY');
    assert.equal(cleanLocationLabel('Austin, TX ( , )'), 'Austin, TX');
    assert.equal(cleanLocationLabel('Austin, TX [ ]'), 'Austin, TX');
  });

  it('keeps brackets that say something', () => {
    assert.equal(cleanLocationLabel('New York, NY (HQ)'), 'New York, NY (HQ)');
    assert.equal(cleanLocationLabel('Remote (US)'), 'Remote (US)');
  });

  it('tidies doubled and dangling separators', () => {
    assert.equal(cleanLocationLabel('Lone Tree, , CO'), 'Lone Tree, CO');
    assert.equal(cleanLocationLabel('Annapolis, MD; ; Lanham, MD'), 'Annapolis, MD; Lanham, MD');
    assert.equal(cleanLocationLabel(', Denver, CO,'), 'Denver, CO');
    assert.equal(cleanLocationLabel('Remote -'), 'Remote');
    assert.equal(cleanLocationLabel('Boston ·  · MA'), 'Boston · MA');
    assert.equal(cleanLocationLabel('Dallas ( ), TX'), 'Dallas, TX');
    assert.equal(cleanLocationLabel('  Miami,   FL  '), 'Miami, FL');
  });

  it('leaves an ordinary location alone', () => {
    assert.equal(cleanLocationLabel('San Mateo, CA, United States'), 'San Mateo, CA, United States');
    assert.equal(cleanLocationLabel('McCarran, NV; San Francisco, California, United States'), 'McCarran, NV; San Francisco, California, United States');
    assert.equal(cleanLocationLabel('Winston-Salem, NC'), 'Winston-Salem, NC');
  });

  it('returns an empty string for nothing, so callers can fall back', () => {
    assert.equal(cleanLocationLabel(undefined), '');
    assert.equal(cleanLocationLabel(null), '');
    assert.equal(cleanLocationLabel(' ( ) '), '');
  });
});
