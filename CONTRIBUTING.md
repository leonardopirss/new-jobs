# Contribuindo

Este projeto usa um fluxo baseado em Git Flow, com revisao obrigatoria por Pull Request.

## Branches permanentes

- `main`: versoes estaveis e prontas para producao.
- `develop`: integracao das tarefas concluidas.

## Fluxo de uma tarefa

1. Atualize a branch `develop`:

   ```bash
   git switch develop
   git pull --ff-only origin develop
   ```

2. Crie uma branch a partir de `develop`:

   ```bash
   git switch -c feature/<numero-ou-descricao-curta>
   ```

   Para correcoes, use `fix/<numero-ou-descricao-curta>`.

3. Implemente e valide a tarefa. Use commits pequenos, com mensagens objetivas.
4. Publique a branch:

   ```bash
   git push -u origin feature/<numero-ou-descricao-curta>
   ```

5. Abra um Pull Request com `develop` como branch de destino e aguarde a revisao.
6. Depois do merge, remova a branch da tarefa no remoto e localmente.

Nao envie commits de tarefas diretamente para `main` ou `develop`. Releases saem de
`develop` para `main` em um Pull Request separado.
