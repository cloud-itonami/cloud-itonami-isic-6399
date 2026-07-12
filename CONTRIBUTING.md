# Contributing

`cloud-itonami-isic-6399` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

## Rules
- Do not commit real operating, personal or credential data.
- Keep publications, delistings, records and disclosures behind the Job Search Portal Governor.
- Treat workflows as high-risk: add tests for publication gating,
  record integrity, disclosure and audit logging.
- Never fabricate a jurisdiction's requirements in `jobsearchops.facts`
  -- every entry cites a real official source.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
