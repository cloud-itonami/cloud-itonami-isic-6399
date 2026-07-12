# Governance

`cloud-itonami-isic-6399` is an OSS open-business blueprint for meta job-search (job-posting aggregation, publication and delisting).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a publication or delisting the governor refuses is never pushed to the public index.
- the Job Search Portal Governor remains independent of the advisor.
- hard policy violations (stale-vacancy publication, pay misstatement, unconsented republication, discriminatory ad content, record-suppression) cannot be overridden by human approval.
- every publication, delisting, sign-off, record and disclose path is auditable.
- sensitive operating and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, posting-accuracy, audit and data-flow review.

Certified operators can lose certification for:
- bypassing posting-accuracy or record policy checks
- mishandling employer, source or job-seeker data
- misrepresenting certification status
- failing to respond to security or accuracy incidents
