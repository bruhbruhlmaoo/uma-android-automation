/** Defines checkbox components. */

package com.steve1316.uma_android_automation.components

object Checkbox : ComponentInterface {
    override val template = Template("components/checkbox/checkbox")
}

object CheckboxDoNotShowAgain : ComponentInterface {
    override val template = Template("components/checkbox/checkbox_do_not_show_again")
}

object CheckboxShopItem : ComponentInterface {
    override val template = Template("components/checkbox/checkbox_shop_item")
}
