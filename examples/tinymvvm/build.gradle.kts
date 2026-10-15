plugins {
    id("agentctl.jvm.library")
}

// TinyApp as MVVM: Jetpack ViewModels under a coordinator, driven through agentctl-runtime's ViewModelDriver. Its
// config sits beside the app here; a host with a release build keeps it in a module of its own, as TinyApp does.
dependencies {
    api(project(":agentctl-runtime"))
    api(libs.androidx.lifecycle.viewmodel)
}
