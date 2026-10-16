import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.SourceUnit

def file = new File('Jenkinsfile')
def text = file.getText('UTF-8')
assert !text.contains('AUTOMATION_SUITE_'): 'Updater credential handling leaked into the dashboard'
assert !text.contains('OCTANE_TEST_SOURCE_DIR')
assert !text.contains('/.env'): 'Dashboard must not create or delete the updater environment file'

def dashboard = new GroovyShell().parse(file)
assert !dashboard.pipelineConfigurationDefaults().containsKey('AUTOMATION_SUITE_CREDENTIAL_ID')
assert dashboard.pipelineConfigurationDefaults().containsKey('ARGOCD_GIT_CREDENTIAL_ID')
def space = [sharedSpaceId: '1', sharedSpaceName: 'Example Space', specific_url: '',
             workspaces: [[workspaceId: '2', workspaceName: 'Example Workspace']]]
def mapping = [shared_url: 'octane-shared-url', shared_spaces: [space]]
def connection = dashboard.resolveOctaneConnection(mapping, '1', '2', 'fixture.json')
assert connection.credentialsId == 'example_space'
assert connection.baseUrl == 'octane-shared-url'
space.apiCredentialId = 'fixture-octane-api'
space.specific_url = 'octane-space-url'
connection = dashboard.resolveOctaneConnection(mapping, '1', '2', 'fixture.json')
assert connection.credentialsId == 'fixture-octane-api'
assert connection.baseUrl == 'octane-space-url'

def example = new groovy.json.JsonSlurper().parse(new File('octane_spaces_mapping.json'))
assert example.shared_url ==~ /[A-Za-z0-9_.-]+/
assert example.shared_spaces.every { !it.specific_url || it.specific_url ==~ /[A-Za-z0-9_.-]+/ }

def source = SourceUnit.create(file.name, text)
source.parse()
source.completePhase()
source.nextPhase()
source.convert()
def credentials = []
source.AST.statementBlock.visit(new CodeVisitorSupport() {
  void visitMethodCallExpression(MethodCallExpression call) {
    if (call.methodAsString == 'octaneSuiteGate') {
      def arguments = call.arguments.expressions.find { it instanceof MapExpression }
      credentials << arguments.mapEntryExpressions.find {
        it.keyExpression.text == 'credentialsId'
      }.valueExpression.text
    }
    super.visitMethodCallExpression(call)
  }
})
assert credentials == ['env.OCTANE_API_CREDENTIAL_ID']
println 'PASS: updater credential code absent; dashboard Octane credential resolution and binding preserved.'
