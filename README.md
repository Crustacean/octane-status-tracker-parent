# octane-status-tracker-parent

## Execution Context

Use the child Jenkinsfile as the Jenkins job entry point. The child checks out both
repositories, copies `variables.yaml`, and loads this Jenkinsfile inside its existing
`node` and `dir('dir1')` blocks on a Linux/Unix agent.

The parent deliberately uses `agent none` and `skipDefaultCheckout(true)`. All stages
and `post` cleanup inherit the child's agent and workspace. No second executor is
requested, and Jenkins must not replace the parent checkout with an implicit checkout
of the child's SCM. Keep `load 'Jenkinsfile'` inside the child's node/directory context;
an absolute `OCTANE_PIPELINE_SOURCE_DIR` alone cannot transfer files between agents.

Do not configure this parent as a standalone Pipeline-from-SCM job. A custom caller
must first allocate a node, check out the parent and supply the configuration, then
load the parent within that same workspace.

## Handoff Regression Check

With Groovy installed and the parent and child repositories checked out side by side:

```sh
groovy tests/pipeline_handoff_test.groovy
```

An optional argument selects a different child repository path. This offline check
parses the actual Jenkinsfiles and verifies the single-node load, inherited workspace,
configuration environment, and disabled automatic checkout. It does not contact
Jenkins or Octane and is not a replacement for a Jenkins integration test.
