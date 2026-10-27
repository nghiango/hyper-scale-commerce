import jenkins.model.Jenkins
import jenkins.install.InstallState
import hudson.model.Node
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import hudson.plugins.git.GitSCM
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.extensions.impl.LocalBranch
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import groovy.json.JsonOutput

def controller = Jenkins.get()
assert controller.isUseSecurity(): 'Refusing to configure an unsecured controller'
def importFile = new File(controller.rootDir, 'secrets/hyperscale-gitops-import')
assert importFile.isFile(): 'Deploy key import file is missing'
def credential = new BasicSSHUserPrivateKey(
    CredentialsScope.GLOBAL, 'gitops-write', 'git',
    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(importFile.text), null,
    'Dedicated HyperScale GitOps write deploy key')
def store = SystemCredentialsProvider.getInstance().getStore()
def domain = Domain.global()
def old = store.getCredentials(domain).find { it.id == 'gitops-write' }
assert old ? store.updateCredentials(domain, old, credential) : store.addCredentials(domain, credential)
assert importFile.delete(): 'Could not remove temporary key import file'

controller.setNumExecutors(0)
def agent = controller.getNode('local-arm64')
if (agent == null) {
    agent = new DumbSlave('local-arm64', '/workspace', new JNLPLauncher(true))
    agent.setNumExecutors(1)
    agent.setLabelString('local-arm64')
    agent.setMode(Node.Mode.EXCLUSIVE)
    agent.setRetentionStrategy(new RetentionStrategy.Always())
    controller.addNode(agent)
}
assert agent.remoteFS == '/workspace': 'Existing agent has an unexpected workspace'

def jobName = 'hyperscale-local-ci'
def job = controller.getItem(jobName)
if (job == null) {
    job = controller.createProject(WorkflowJob.class, jobName)
}
assert job instanceof WorkflowJob: 'Existing item is not a Pipeline job'
def scm = new GitSCM(
    [new UserRemoteConfig('https://github.com/nghiango/hyper-scale-commerce.git', null, null, null)],
    [new BranchSpec('*/main')], false, [], null, null, [new LocalBranch('main')])
def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
definition.setLightweight(true)
job.setDefinition(definition)
job.setDescription('Trusted main-branch ARM64 build and GitOps publication. Source repository must contain the CI configuration; no build is started by bootstrap.')
job.setTriggers([new hudson.triggers.SCMTrigger('H/2 * * * *')])
def sourceReady = __SOURCE_CI_AVAILABLE__
job.setDisabled(!sourceReady)
job.save()
def location = jenkins.model.JenkinsLocationConfiguration.get()
location.setUrl('http://localhost:8090/')
location.save()
controller.setInstallState(InstallState.INITIAL_SETUP_COMPLETED)
controller.save()
println JsonOutput.toJson([
    credentialId: 'gitops-write', jobName: jobName,
    agentName: 'local-arm64', agentSecret: controller.getComputer('local-arm64').getJnlpMac(),
    securityEnabled: controller.isUseSecurity(), version: Jenkins.VERSION,
    polling: 'H/2 * * * *', jobDisabled: job.isDisabled()
])
