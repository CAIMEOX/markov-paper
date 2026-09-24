package com.caimeo.markovpaper.assemblage

object VanillaStructureCatalogs {
    fun open(
        minecraftVersion: String,
        source: StructureAssetSource,
    ): CatalogSnapshot = StructureLibrary(
        manifest = BundledStructureManifests.load(
            resourcePath = "/vanilla/$minecraftVersion/structures.txt",
            revision = CatalogRevision("minecraft-$minecraftVersion-structures-v1"),
        ),
        source = source,
    ).open()
}
