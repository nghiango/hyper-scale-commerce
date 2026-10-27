#!/usr/bin/env python3
"""Verify local Jenkins enrollment and real deploy-key use without displaying secrets."""
import base64
import http.cookiejar
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parents[2]
password = subprocess.check_output([
    'docker', 'exec', 'hyperscale-local-ci-jenkins-1',
    'cat', '/var/jenkins_home/secrets/initialAdminPassword'],
    text=True, stderr=subprocess.PIPE).strip()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
headers = {'Authorization': 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()}
base = 'http://localhost:8090'


def get(path):
    with opener.open(urllib.request.Request(base + path, headers=headers), timeout=30) as response:
        return response.read()


crumb = json.loads(get('/crumbIssuer/api/json'))
headers[crumb['crumbRequestField']] = crumb['crumb']


def groovy(script):
    data = urllib.parse.urlencode({'script': script}).encode()
    with opener.open(urllib.request.Request(base + '/scriptText', data=data, headers=headers),
                     timeout=60) as response:
        return json.load(response)


deadline = time.monotonic() + 90
while True:
    agent = json.loads(get('/computer/local-arm64/api/json?tree=displayName,offline'))
    if not agent['offline']:
        break
    if time.monotonic() > deadline:
        raise SystemExit('ARM64 agent did not become online within 90 seconds')
    time.sleep(1)

state = groovy('''
import jenkins.model.Jenkins
import groovy.json.JsonOutput
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
 def j = Jenkins.get()
 println JsonOutput.toJson([
   credentialPresent: SystemCredentialsProvider.getInstance().getStore().getCredentials(Domain.global()).any { it.id == 'gitops-write' },
   jobPresent: j.getItem('hyperscale-local-ci') != null,
   failedPlugins: j.pluginManager.failedPlugins.collect { it.name },
   plugins: j.pluginManager.plugins.collect { [id: it.shortName, version: it.version] }.sort { it.id }
 ])
''')
assert state['credentialPresent'] and state['jobPresent'] and not state['failedPlugins'], 'Jenkins configuration verification failed'
lock = '\n'.join(p['id'] + ':' + p['version'] for p in state.pop('plugins')) + '\n'
(root / 'local-ci/jenkins/plugins.lock').write_text(lock)
hosts = json.dumps((root / 'local-ci/jenkins/github_known_hosts').read_text())
pipeline = """node('local-arm64') {
  writeFile(file: 'github_known_hosts', text: HOSTS_LITERAL)
  sshagent(credentials: ['gitops-write']) {
    sh '''set -eu
export GIT_SSH_COMMAND="ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$PWD/github_known_hosts"
git ls-remote git@github.com:nghiango/hyper-scale-commerce-gitops.git refs/heads/main
'''
  }
  deleteDir()
}
""".replace('HOSTS_LITERAL', hosts)
encoded = base64.b64encode(pipeline.encode()).decode()
job = 'hyperscale-gitops-access-check'
groovy('''
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import groovy.json.JsonOutput
 def j = Jenkins.get()
 def job = j.getItem('JOB_NAME') ?: j.createProject(WorkflowJob.class, 'JOB_NAME')
 assert job instanceof WorkflowJob
 def script = new String(Base64.decoder.decode('PIPELINE_BASE64'), 'UTF-8')
 job.setDefinition(new CpsFlowDefinition(script, true))
 job.save()
 def queued = job.scheduleBuild2(0)
 assert queued != null
 println JsonOutput.toJson([queued: true])
'''.replace('JOB_NAME', job).replace('PIPELINE_BASE64', encoded))
deadline = time.monotonic() + 120
while True:
    try:
        build = json.loads(get('/job/' + job + '/lastBuild/api/json?tree=number,building,result'))
        if not build['building'] and build['result']:
            break
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    if time.monotonic() > deadline:
        raise SystemExit('Deploy-key smoke job did not finish within 120 seconds')
    time.sleep(1)
(root / 'build').mkdir(exist_ok=True)
(root / 'build/local-ci-jenkins-key-check.log').write_bytes(get('/job/' + job + '/lastBuild/consoleText'))
assert build['result'] == 'SUCCESS', 'Jenkins deploy-key smoke job failed; inspect the ignored local build log'
groovy("import jenkins.model.Jenkins; import groovy.json.JsonOutput; Jenkins.get().getItem('" + job + "').delete(); println JsonOutput.toJson([removed: true])")
print(json.dumps({'agentOnline': True, **state, 'deployKeySmokeTest': build['result']}))
print('Installed plugin versions recorded; smoke-job console stored under ignored build/.')
