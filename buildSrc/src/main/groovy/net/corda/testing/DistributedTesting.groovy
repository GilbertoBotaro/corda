package net.corda.testing

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test

/**
 This plugin is responsible for wiring together the various components of test task modification
 */
class DistributedTesting implements Plugin<Project> {

    /*
    Example run:
    ./gradlew runAllTestWorker "-PparallelTestTasks=integrationTest" "-PparallelTestWorkers=6" "-PparallelTestWorkerId=3"
     */

    @Override
    void apply(Project project) {
        def groupTask = project.tasks.register("groupAllTests", GroupTests) {
            it.subprojects = true
        }
        // runAllTestWorker has intentionally different name than runTestWorker.
        // this is because if the name was the same, calling ./gradlew runTestWorker
        // launches all of these tasks which is not what we want.
        // user has to qualify the task which might confuse some
        def run = project.tasks.register("runAllTestWorker", RunTestWorker) {
            it.group = "parallel builds"
            it.dependsOn groupTask
        }
        project.subprojects { Project p ->
            p.pluginManager.apply(DistributedTestModule)
            p.tasks.withType(ListTests) { listing -> groupTask.configure { it.dependsOn(listing) } }
            p.tasks.withType(Test)
                    .findAll { DistributedTestingDynamicParameters.shouldRunTest(p, it) }
                    .forEach { test -> run.configure { it.finalizedBy(test) } }
        }
    }

}

class DistributedTestModule implements Plugin<Project> {
    @Override
    void apply(Project target) {
        def list = target.tasks.register("listTests", ListTests)
        def group = target.tasks.register("groupTests", GroupTests) {
            it.dependsOn list
            it.subprojects = false
        }
        def run = target.tasks.register("runTestWorker", RunTestWorker) {
            it.dependsOn group
        }
        target.tasks.withType(Test)
                .findAll { DistributedTestingDynamicParameters.shouldRunTest(target, it) }
                .forEach { test ->
                    list.configure {
                        it.dependsOn(test.name + "Classes")
                    }
                    run.configure { it.finalizedBy(test) }
                }
    }
}

class GroupTests extends DefaultTask {
    boolean subprojects = false
    Map<Test, Set<String>> testsToRun = new HashMap<>()

    @TaskAction
    def group() {
        logger.lifecycle("Grouping tests for $project")
        Collection<ListTests> listers = project.tasks.withType(ListTests)
        if (subprojects) {
            logger.lifecycle("Including subprojects")
            project.subprojects { Project sub ->
                listers += sub.tasks.withType(ListTests)
            }
        }

        listers.forEach { ListTests it ->
            // TODO proper grouping
            it.tests.forEach { test, tests ->
                testsToRun.put(test, tests.take(1).toSet())
            }
        }
    }

    Set<String> includesForTest(Test t) {
        return testsToRun.get(t, new HashSet<>())
    }
}

class RunTestWorker extends DefaultTask {
    RunTestWorker() {
        group = "parallel builds"
    }

    @TaskAction
    def run() {
        println "Configuring test tasks"
        def grouper = project.tasks.withType(GroupTests).first()
        project.subprojects { Project p ->
            p.tasks.withType(Test)
                    .findAll { DistributedTestingDynamicParameters.shouldRunTest(project, it) }
                    .forEach { Test t ->
                        if (t.hasProperty("ignoreForDistribution")) {
                            return
                        }
                        def includes = grouper.includesForTest(t)
                        println "Configuring test for includes: $t: $includes"
                        t.configure {
                            doFirst {
                                println "Running modified test: $t"
                                filter {
                                    includes.forEach {
                                        includeTestsMatching it
                                    }
                                }
                            }
                        }
//                    t.testNameIncludePatterns = tests
                    }
        }
        println "Running worker tests"
    }
}

class DistributedTestingDynamicParameters {

    static boolean shouldRunTest(Project project, Test test) {
        def targets = testTaskNames(project)
        if (targets == ["all"]) return true
        if (!targets.contains(test.name)) return false
        return !test.hasProperty("ignoreForDistribution")
    }

    static Set<String> testTaskNames(Project project) {
        return ((project.property("parallelTestTasks") as String) ?: "all")
                .split(",")
                .toList()
                .toSet()
    }

    static int workerNumber(Project project) {
        return (project.property("parallelTestWorkerId") as Integer) ?: 0
    }

    static int numberOfWorkers(Project project) {
        return (project.property("parallelTestWorkers") as Integer) ?: 1
    }
}