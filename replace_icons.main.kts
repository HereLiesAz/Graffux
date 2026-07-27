import java.io.File

fun main() {
    val file = File("c:/Users/azrie/StudioProjects/Graffux/app/src/main/java/com/hereliesaz/graffux/MainActivity.kt")
    var text = file.readText()
    text = text.replace("DesignR.drawable.ic_ps_brush", "TablerIcons.Brush")
    text = text.replace("DesignR.drawable.ic_ps_blur", "TablerIcons.HandFinger")
    text = text.replace("DesignR.drawable.ic_ps_eraser", "TablerIcons.Eraser")
    text = text.replace("DesignR.drawable.ic_ps_pencil", "TablerIcons.Pencil")
    text = text.replace("DesignR.drawable.ic_ps_liquify", "TablerIcons.Ripple")
    text = text.replace("DesignR.drawable.ic_ps_heal", "TablerIcons.Bandage")
    text = text.replace("DesignR.drawable.ic_ps_burn", "TablerIcons.Flame")
    text = text.replace("DesignR.drawable.ic_ps_dodge", "TablerIcons.Sun")
    text = text.replace("DesignR.drawable.ic_ps_color", "TablerIcons.Palette")
    text = text.replace("DesignR.drawable.ic_ps_fill", "TablerIcons.Bucket")
    text = text.replace("DesignR.drawable.ic_ps_select", "TablerIcons.Focus")
    text = text.replace("DesignR.drawable.ic_ps_invert", "TablerIcons.Exchange")
    text = text.replace("DesignR.drawable.ic_ps_deselect", "TablerIcons.X")
    text = text.replace("DesignR.drawable.ic_ps_quickmenu", "TablerIcons.Bolt")
    text = text.replace("DesignR.drawable.ic_ps_circle", "TablerIcons.Circle")
    text = text.replace("DesignR.drawable.ic_ps_symmetry", "TablerIcons.LayersLinked")
    text = text.replace("painterResource(DesignR.drawable.ic_ps_fit)", "rememberVectorPainter(TablerIcons.Maximize)")
    text = text.replace("painterResource(DesignR.drawable.ic_ps_undo)", "rememberVectorPainter(TablerIcons.ArrowBackUp)")
    text = text.replace("painterResource(DesignR.drawable.ic_ps_redo)", "rememberVectorPainter(TablerIcons.ArrowForwardUp)")
    
    // Add imports
    val importIdx = text.indexOf("import androidx.compose.ui.unit.dp")
    val imports = """
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
""".trimIndent()
    text = text.substring(0, importIdx) + imports + "\n" + text.substring(importIdx)
    
    file.writeText(text)
}
