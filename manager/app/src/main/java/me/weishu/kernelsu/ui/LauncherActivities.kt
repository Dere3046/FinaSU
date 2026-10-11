package me.weishu.kernelsu.ui

// Separate targets give the system starting window the selected icon's theme
// before onCreate runs. Activity aliases cannot override their target's theme.
class GeometryLauncherActivity : MainActivity()

class KernelSULauncherActivity : MainActivity()
