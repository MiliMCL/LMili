plugins {
    `java-library`
}

group = "fun.bm.mili"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.menthamc.org/repository/maven-public/")
}

dependencies {
    // LMili API (from root project)
    compileOnly(project(":lmili-api"))
    
    // Other dependencies are transitive from lmili-api
}

tasks.jar {
    archiveFileName.set("LMiliTestPlugin.jar")
}

// 构建后自动复制到服务器 plugins 目录（使用 E:\Temp 中转避免 Program Files 文件消失问题）
tasks.register<Copy>("deployToServer") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into("E:/Temp/plugins")
    doLast {
        println("Deployed to E:/Temp/plugins/${tasks.jar.get().archiveFile.get().asFile.name}")
    }
}
