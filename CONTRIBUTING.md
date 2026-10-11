# Contributing

This project uses a Git Flow-based workflow with mandatory pull-request review.

## Permanent branches

- `main`: stable, production-ready releases.
- `develop`: integration branch for completed tasks.

## Task workflow

1. Update `develop`:

   ```bash
   git switch develop
   git pull --ff-only origin develop
   ```

2. Create a branch from `develop`:

   ```bash
   git switch -c feature/<numero-ou-descricao-curta>
   ```

   Use `fix/<number-or-short-description>` for corrections.

3. Complete and validate the task. Use small commits with clear messages.
4. Push the branch:

   ```bash
   git push -u origin feature/<numero-ou-descricao-curta>
   ```

5. Open a pull request with `develop` as its base branch and wait for review.
6. After the merge, delete the task branch remotely and locally.

Do not push task commits directly to `main` or `develop`. Releases move from `develop` to `main`
through a separate reviewed pull request.
