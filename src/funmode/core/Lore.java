package funmode.core;

import arc.Core;
import mindustry.ctype.UnlockableContent;

/**
 * Bolts cursed flavour onto vanilla content's campaign text, pulled from a mod bundle key. Must be
 * called AFTER content init (e.g. from a curse's {@link funmode.core.Curse#init()}, which runs on
 * ClientLoadEvent) - content init loads description/details from bundle keys and would otherwise
 * clobber anything set earlier. Appends rather than replaces, so vanilla briefings/lore survive, and
 * is idempotent (a second call with the same text is a no-op), so it's safe if ClientLoad re-fires.
 */
public final class Lore{
    private Lore(){
    }

    /** Append to the DATABASE details - the extra description the campaign shows once a block is
     * researched ({@code hideDetails} keeps it campaign-only). Use for blocks. */
    public static void details(UnlockableContent c, String key){
        if(c == null) return;
        String extra = Core.bundle.getOrNull(key);
        if(extra == null || extra.isEmpty()) return;
        if(c.details != null && c.details.contains(extra)) return; //already applied
        c.details = (c.details == null || c.details.isEmpty()) ? extra : c.details + "\n\n" + extra;
        c.hideDetails = true;
    }

    /** Append to the DESCRIPTION - the briefing shown when launching to a sector. Use for sectors. */
    public static void description(UnlockableContent c, String key){
        if(c == null) return;
        String extra = Core.bundle.getOrNull(key);
        if(extra == null || extra.isEmpty()) return;
        if(c.description != null && c.description.contains(extra)) return; //already applied
        c.description = (c.description == null || c.description.isEmpty()) ? extra : c.description + "\n\n" + extra;
    }
}
