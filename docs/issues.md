# Issues and labels

[Back to Minecraft Ring](../README.md)

Open the [issue chooser](https://github.com/siddoff/Minecraft-Ring/issues/new/choose)
and select **Bug report**, **Feature request**, or **Question or setup help**.
English and Russian reports are welcome. Search existing issues first.

Bug reports ask for the mod revision, game versions, GPU and driver, location,
reproduction steps and relevant logs. Feature requests ask for the use case
and proposed behavior. Setup questions ask what you are trying to do and what
you have already checked. A blank issue remains available for other topics.

## Automatic labels

| Form | Type label |
| --- | --- |
| Bug report | `bug` |
| Feature request | `enhancement` |
| Question or setup help | `question` |

New issues receive `status: needs triage`. The form's **Affected area** field
also maps to a component label through the `Label issues` GitHub Actions workflow:

| Affected area | Label |
| --- | --- |
| Rendering and overlay | `area: rendering` |
| Terrain and collision | `area: terrain` |
| Moving platforms | `area: platforms` |
| Controls and F8 | `area: controls` |
| Combat and interactions | `area: combat` |
| Build and installation | `area: setup` |
| Other / not sure | No component label |

Editing this field updates the component label on an open issue. Freeform
reports with no recognized area leave component labels alone. The workflow
does not change type, priority or review-status labels on edits.

## Reviewing reports

Use one current review status: `status: needs triage`, `status: needs info`,
`status: confirmed`, or `status: in progress`. Remove the previous status as
the report moves forward. Closing an issue records its completion normally.

Use `priority: high` for major blockers and `priority: low` for minor polish;
no priority label means normal priority. Existing GitHub labels such as
`duplicate`, `documentation`, `help wanted` and `good first issue` remain
available for manual categorization.

Forms live in `.github/ISSUE_TEMPLATE/`, automation in
`.github/workflows/issue-labels.yml`, and label names, colors and descriptions
in `.github/labels.json`. Labels must also exist in GitHub for form defaults
to apply. If you change area options, update the forms, workflow and label
definitions together.
