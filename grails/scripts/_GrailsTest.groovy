/*
* Copyright 2004-2005 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.support.PersistenceContextInterceptor
import org.codehaus.groovy.grails.web.context.GrailsConfigUtils
import grails.util.GrailsUtil

import org.springframework.beans.factory.NoSuchBeanDefinitionException

import org.codehaus.groovy.grails.test.junit3.JUnit3GrailsTestTypeRunnerFactory
import org.codehaus.groovy.grails.test.junit3.JUnit3GrailsIntegrationTestTypeRunnerFactory

/**
 * Gant script that runs the Grails unit tests
 *
 * @author Graeme Rocher
 *
 * @since 0.4
 */

includeTargets << grailsScript("_GrailsBootstrap")
includeTargets << grailsScript("_GrailsRun")

// The four test phases that we can run.
unitTests = [ "unit" ]
integrationTests = [ "integration" ]
functionalTests = []
otherTests = [ "cli" ]

// The phases that we will run on this execution. Override this in your
// own scripts to control the phases and their order.
phasesToRun = []

// A list of test names. These can be of any of this forms:
//
//   org.example.*
//   org.example.**.*
//   MyService
//   MyService.testSomeMethod
//   org.example.other.MyService.testSomeMethod
//
// The default pattern runs all tests.
testNames = buildConfig.grails.testing.patterns ?: ['**.*']

// Controls which result formats are generated. By default both XML
// and plain text files are created. You can override this in your
// own scripts.
reportFormats = [ "xml", "plain" ]

// If true, only run the tests that failed before.
reRunTests = false

// Where the report files are created.
testReportsDir = grailsSettings.testReportsDir

// Set up an Ant path for the tests.
ant.path(id: "grails.test.classpath", testClasspath)

createTestReports = true
compilationFailures = []

testHelper = null
testsFailed = false

target(allTests: "Runs the project's tests.") {
    depends(compile, packagePlugins)
    packageFiles(basedir)

    ant.mkdir(dir: testReportsDir)
    ant.mkdir(dir: "${testReportsDir}/html")
    ant.mkdir(dir: "${testReportsDir}/plain")


    // If we are to run the tests that failed, replace the list of
    // test names with the failed ones.
    if (reRunTests) testNames = getFailedTests()

    // If no phases are explicitly configured, run them all.
    if (!phasesToRun) phasesToRun = [ "unit", "integration", "functional", "other" ]
    
    event("TestPhasesStart", [phasesToRun])

    try {
        // Process the tests in each phase that is configured to run.
        for(phase in phasesToRun) {
            // Skip this phase if there are no test types registered for it.
            def testTypes = this."${phase}Tests"
            if (!testTypes) continue

            // Add a blank line before the start of this phase so that it
            // is easier to distinguish
            println()

            event("StatusUpdate", ["Starting $phase test phase"])
            event("TestPhaseStart", [phase])

            "${phase}TestPhasePreparation"()

            // Now run all the tests registered for this phase.
            testTypes.each(processTests)

            // Perform any clean up required.
            this."${phase}TestPhaseCleanUp"()

            event("TestPhaseEnd", [phase])
        }

    } finally {
        String msg = testsFailed ? "\nTests FAILED" : "\nTests PASSED"
        if (createTestReports) {
            produceReports()
            msg += " - view reports in ${testReportsDir}."
        }

        event("StatusFinal", [msg])

        event("TestPhasesEnd", [])
        
    }


    return testsFailed ? 1 : 0
}

/**
 * Compiles and runs all the tests of the given type and then generates
 * the reports for them.
 * @param type The type of the tests to compile (not the test phase!)
 * For example, "unit", "jsunit", "webtest", etc.
 */
processTests = { String type ->
    if (new File("${basedir}/test/$type").exists()) {
        println "\nRunning tests of type '$type'"
    
        // First compile the test classes.
        compileTests(type)

        // Run them.
        runTests(type)
    } else {
        println "Skipping '$type' tests (test/$type doesn't exist)"
    }
}

/**
 * Compiles all the test classes for a particular type of test, for
 * example "unit" or "webtest". Assumes that the source files are in
 * the "test/$type" directory. It also compiles the files to distinct
 * directories for each test type: "$testClassesDir/$type".
 * @param type The type of the tests to compile (not the test phase!)
 * For example, "unit", "jsunit", "webtest", etc.
 */
compileTests = { String type ->
    event("TestCompileStart", [type])

    srcdir = new File("${basedir}/test/${type}")
    if(srcdir.exists()) {        
        def destDir = new File(grailsSettings.testClassesDir, type)
        ant.mkdir(dir: destDir.path)
        try {
            def classpathId = "grails.test.classpath"
            ant.groovyc(destdir: destDir,
                    encoding:"UTF-8",
                    classpathref: classpathId) {
                javac(classpathref:classpathId, debug:"yes")

                src(path:srcdir)
            }
        }
        catch (Exception e) {
            event("StatusFinal", ["Compilation error compiling [$type] tests: ${e.message}"])
            exit 1
        }
    }

    event("TestCompileEnd", [type])
}

runTests = { String type ->
    def prevContextClassLoader = Thread.currentThread().contextClassLoader
    try {
        
        def preparer = binding.variables."${type}TestsPreparation"
        def runner = (preparer) ? preparer() : JUnit3GrailsTestTypeRunnerFactory.createRunner(type, binding)
        
        if (runner.testCount == 0) {
            event("StatusUpdate", ["No tests found in test/$type to execute"])
            return
        }

        // Set the context class loader to the one used to load the tests.
        Thread.currentThread().contextClassLoader = runner.testClassLoader

        event("TestSuiteStart", [type])
        println "-------------------------------------------------------"
        println "Running ${runner.testCount} $type test${runner.testCount > 1 ? 's' : ''}..."

        def start = new Date()
        def result = runner.run()
        def end = new Date()

        event("TestSuiteEnd", [type])
        event("StatusUpdate", ["Tests Completed in ${end.time - start.time}ms"])

        println "-------------------------------------------------------"
        println "Tests passed: ${result.passCount}"
        println "Tests failed: ${result.failCount}"
        println "-------------------------------------------------------"

        // If any of the tests fail, we register the whole test run as
        // a failure.
        if (result.failCount > 0) testsFailed = true
    }
    catch (Exception e) {
        event("StatusFinal", ["Error running $type tests: ${e.toString()}"])
        GrailsUtil.deepSanitize(e)
        e.printStackTrace()
        testsFailed = true
    }
    finally {
        Thread.currentThread().contextClassLoader = prevContextClassLoader
        binding.variables."${type}TestsCleanUp"?.call()
    }
}

unitTestPhasePreparation = {}
unitTestPhaseCleanUp = {}

getPersistenceInterceptor = {
    try { 
        appCtx.getBean('persistenceInterceptor')
    } catch (NoSuchBeanDefinitionException) {
        null
    }
}

/**
 * Initialises a persistence context and bootstraps the application.
 */
integrationTestPhasePreparation = {
    packageTests()
    bootstrap()

    // Get the Grails application instance created by the bootstrap
    // process.
    def app = appCtx.getBean(GrailsApplication.APPLICATION_ID)
    if (app.parentContext == null) {
        app.applicationContext = appCtx
    }

    getPersistenceInterceptor()?.init()

    def servletContext = classLoader.loadClass("org.springframework.mock.web.MockServletContext").newInstance()
    GrailsConfigUtils.configureServletContextAttributes(servletContext, app, pluginManager, appCtx) 
    GrailsConfigUtils.executeGrailsBootstraps(app, appCtx, servletContext)
}

/**
 * Shuts down the bootstrapped Grails application.
 */
integrationTestPhaseCleanUp = {
    // Kill any context interceptor we might have.
    getPersistenceInterceptor()?.destroy()

    shutdownApp()
}

/**
 * Starts up the test server.
 */
functionalTestPhasePreparation = {
    packageApp()
    runApp()
}

/**
 * Shuts down the test server.
 */
functionalTestPhaseCleanUp = {
    stopServer()
}

otherTestPhasePreparation = {}
otherTestPhaseCleanUp = {}

_defaultTestsPreparation = {
    
}
integrationTestsPreparation = {
    JUnit3GrailsIntegrationTestTypeRunnerFactory.createRunner('integration', binding)
}

target(packageTests: "Puts some useful things on the classpath for integration tests.") {
    ant.copy(todir: new File(grailsSettings.testClassesDir, "integration").path) {
        fileset(dir: "${basedir}", includes: metadataFile.name)
    }
    ant.copy(todir: grailsSettings.testClassesDir.path, failonerror: false) {
        fileset(dir: "${basedir}/grails-app/conf", includes: "**", excludes: "*.groovy, log4j*, hibernate, spring")
        fileset(dir: "${basedir}/grails-app/conf/hibernate", includes: "**/**")
        fileset(dir: "${basedir}/src/java") {
            include(name: "**/**")
            exclude(name: "**/*.java")
        }
        fileset(dir: "${basedir}/test/unit") {
            include(name: "**/**")
            exclude(name: "**/*.java")
            exclude(name: "**/*.groovy")
        }
        fileset(dir: "${basedir}/test/integration") {
            include(name: "**/**")
            exclude(name: "**/*.java")
            exclude(name: "**/*.groovy")
        }
    }
}

target(produceReports: "Outputs aggregated xml and html reports") {
    ant.junitreport(todir: "${testReportsDir}") {
        fileset(dir: testReportsDir) {
            include(name: "TEST-*.xml")
        }
        report(format: "frames", todir: "${testReportsDir}/html")
    }
}

def getFailedTests() {
    File file = new File("${testReportsDir}/TESTS-TestSuites.xml")
    if (file.exists()) {
        def xmlParser = new XmlParser().parse(file)
        def failedTests = xmlParser.testsuite.findAll { it.'@failures' =~ /.*[1-9].*/ || it.'@errors' =~ /.*[1-9].*/}

        return failedTests.collect {
            String testName = it.'@name'
            testName = testName.replace('Tests', '')
            def pkg = it.'@package'
            if (pkg) {
                testName = pkg + '.' + testName
            }
            return testName
        }
    } else {
        return []
    }
}
