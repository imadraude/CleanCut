# AGENTS.md

## Agent skills

### Issue tracker

GitHub Issues via `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`CONTEXT.md` and `docs/adr/` at repo root). See `docs/agents/domain.md`.

### Build & Verification

- **No heavy local builds**: Do NOT run heavy Gradle build or compilation commands (`./gradlew assemble*`, `./gradlew compile*`, etc.) locally inside Termux on Android devices. Local builds consume extreme CPU, RAM, and battery resources on mobile hardware.
- **CI verification via GitHub Actions**: Commit and push changes to GitHub, then monitor and verify builds through the `gh` CLI:
  - Check status: `gh run list -L 1`
  - Watch build: `gh run watch`


<!-- antislop:start -->
## antislop
For UI, copy, people, mobile layout, or code comments work, read `antislop.md` (core) and then the skill for the task:
- UI / visual: `skills/antislop-ui/SKILL.md`
- Copy & text: `skills/antislop-copywriting/SKILL.md`
- People: `skills/antislop-human/SKILL.md`
- Mobile / responsive: `skills/antislop-layoutmobile/SKILL.md`
- Code comments: `skills/antislop-code/SKILL.md`
Before starting, ask the user when antislop applies: during the work, or after it is done.
<!-- antislop:end -->
