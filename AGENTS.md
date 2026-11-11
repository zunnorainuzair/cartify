# Cartify — agent instructions

This file is loaded automatically by DSH at the start of every session in this project.

## Read first

Before doing anything else, read **`docs/PROJECT.md`**. It is the single source of truth:
architecture, build commands, exact tool paths, hard-won operational lessons, current
uncommitted state, known defects and the roadmap. It exists specifically so a session with
no memory of previous work can recover the full picture without guessing.

Do not start a new dated document in `docs/` for state that belongs there. Update
`docs/PROJECT.md` instead, and leave dated files as history.

## Non-negotiables in this project

- **Never push to GitHub without an explicit instruction in the current conversation.**
  The remote is public. `origin/master` has stayed at `b133706` throughout recent work.
- **No embedded API keys.** Search uses keyless catalogues (Deezer, iTunes); lyrics use
  LRCLIB. Do not add a service that requires an app-level key without asking first.
- **Verify every edit before claiming it worked.** The `edit` tool has repeatedly reported
  success while changing nothing. Confirm with grep, then build, then check the device.
- **Build success is the string `BUILD SUCCESSFUL`, not exit code 0** — Gradle writes
  deprecation notes to stderr, so PowerShell often reports exit code 1 on a good build.

## Writing files here

Java sources use CRLF. Use `UTF8Encoding($false)` (no BOM) when rewriting them, and prefer
line-index splicing over multi-line `.Replace()`. Check for mojibake afterwards. Details
and snippets are in section 4 of `docs/PROJECT.md`.
