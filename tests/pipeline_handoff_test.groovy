import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit

class HandoffCalls extends CodeVisitorSupport {
  final List<Map> calls = []
  private final List<MethodCallExpression> parents = []

  void visitMethodCallExpression(MethodCallExpression call) {
    calls << [call: call, parents: new ArrayList(parents)]
    parents.add(call)
    super.visitMethodCallExpression(call)
    parents.remove(parents.size() - 1)
  }
}

def parseCalls = { File file ->
  assert file.isFile(): "Missing Jenkinsfile: ${file}"
  def source = SourceUnit.create(file.name, file.getText('UTF-8'))
  source.parse()
  source.completePhase()
  source.nextPhase()
  source.convert()
  def visitor = new HandoffCalls()
  source.AST.statementBlock.visit(visitor)
  source.AST.methods.each { it.code?.visit(visitor) }
  visitor.calls
}

def constantArgument = { MethodCallExpression call, value ->
  call.arguments.expressions.any {
    it instanceof ConstantExpression && it.value == value
  }
}

File parentFile = new File('Jenkinsfile')
File childFile = new File(args ? args[0] : '../octane-status-tracker-child', 'Jenkinsfile')
def parent = parseCalls(parentFile)
def child = parseCalls(childFile)
def pipeline = parent.findAll { it.call.methodAsString == 'pipeline' }
assert pipeline.size() == 1
def agents = parent.findAll { it.call.methodAsString == 'agent' }
assert agents.size() == 1: 'Parent must not allocate stage-specific agents'
assert agents[0].parents == [pipeline[0].call]: 'agent none must be at Pipeline root'
def agentArguments = agents[0].call.arguments.expressions
assert agentArguments.size() == 1
assert agentArguments[0] instanceof VariableExpression && agentArguments[0].name == 'none':
    'Parent must inherit the child executor with agent none'
assert !parent.any { it.call.methodAsString in ['node', 'ws', 'checkout'] }:
    'Parent must retain the caller workspace without allocating or checking out again'

def checkout = parent.findAll { it.call.methodAsString == 'skipDefaultCheckout' }
assert checkout.size() == 1 && constantArgument(checkout[0].call, true)
assert checkout[0].parents*.methodAsString == ['pipeline', 'options']:
    'Disable Declarative automatic checkout for the entire loaded Pipeline'

def nodes = child.findAll { it.call.methodAsString == 'node' }
assert nodes.size() == 1: 'Child must allocate exactly one executor'
def loads = child.findAll { it.call.methodAsString == 'load' }
assert loads.size() == 1 && constantArgument(loads[0].call, 'Jenkinsfile')
def ancestors = loads[0].parents
assert ancestors.contains(nodes[0].call): 'Parent load must remain inside the child node'
assert ancestors.any { it.methodAsString == 'dir' && constantArgument(it, 'dir1') }:
    'Parent load must retain the parent checkout directory'
assert ancestors.any { it.methodAsString == 'withEnv' }:
    'Parent load must retain the transported configuration environment'
assert !ancestors.any { it.methodAsString in ['parallel', 'ws'] }

def sourceScript = new GroovyShell().parse(parentFile)
def environment = new Expando(OCTANE_PIPELINE_SOURCE_DIR: '/agent/workspace/job/dir1')
assert sourceScript.resolvePipelineSourcePath('octane_spaces_mapping.json',
    environment) ==
    '/agent/workspace/job/dir1/octane_spaces_mapping.json'
assert sourceScript.resolvePipelineSourcePath('/custom/mapping.json',
    environment) == '/custom/mapping.json'

println 'PASS: one child node; parent inherits agent, checkout and configuration; no implicit checkout.'
