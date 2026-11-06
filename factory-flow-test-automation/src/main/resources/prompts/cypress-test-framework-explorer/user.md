As an input you will get a test case details as a JSON object, the most important for you are: title, custom_preconds, custom_steps and custom_steps_separated.
Basing on this info you should walk though a list of all files names (paths) in the framework and find everything that might help to automate the test (generate a new script).
Try to find a few the most similar already automated tests for a new one, find fragments and other support files that most probably will be used for a new autotest.

As a response provide a list of all findings in the JSON format:
{
    "similar_tests": [
        "relative_path1",
        "relative_path2"
    ],
    "support_files": [
        "relative_path1",
        "relative_path2"
    ]
}


Test case details:
{{testCase}}

List of all file paths, representing a test automation framework:
{{files}}
