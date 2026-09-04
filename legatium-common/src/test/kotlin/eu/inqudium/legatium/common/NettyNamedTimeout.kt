package eu.inqudium.legatium.common

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Test-only: defines a class under a Netty timeout name (a subclass of `java.lang.Exception`) in a
 * throwaway classloader, so the by-name classification can be proven without a Netty dependency on this
 * module's test classpath. The bytecode is a minimal, hand-assembled class file - a public no-arg
 * constructor calling `Exception.<init>()` - whose constant pool is static apart from the name, so no
 * bytecode library is needed either.
 */
internal object NettyNamedTimeout {
    const val READ_TIMEOUT = "io.netty.handler.timeout.TimeoutException"
    const val CONNECT_TIMEOUT = "io.netty.channel.ConnectTimeoutException"

    fun create(name: String = READ_TIMEOUT): Throwable {
        val loader =
            object : ClassLoader(NettyNamedTimeout::class.java.classLoader) {
                override fun findClass(requested: String): Class<*> {
                    if (requested != name) throw ClassNotFoundException(requested)
                    val bytes = classFile(name)
                    return defineClass(requested, bytes, 0, bytes.size)
                }
            }
        return loader.loadClass(name).getConstructor().newInstance() as Throwable
    }

    /**
     * A minimal class file: `public class <name> extends java/lang/Exception { public <init>() { super(); } }`,
     * class-file version 52 (Java 8) - old enough for every JVM this project targets, and verifiable
     * without a StackMapTable.
     */
    private fun classFile(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(0xCAFEBABE.toInt())
        d.writeShort(0) // minor
        d.writeShort(52) // major: Java 8
        // Constant pool
        d.writeShort(11) // count + 1
        d.writeByte(7) // #1 Class -> #2
        d.writeShort(2)
        d.writeByte(1) // #2 Utf8 this class
        d.writeUTF(name.replace('.', '/'))
        d.writeByte(7) // #3 Class -> #4
        d.writeShort(4)
        d.writeByte(1) // #4 Utf8 super class
        d.writeUTF("java/lang/Exception")
        d.writeByte(1) // #5 Utf8 <init>
        d.writeUTF("<init>")
        d.writeByte(1) // #6 Utf8 ()V
        d.writeUTF("()V")
        d.writeByte(12) // #7 NameAndType -> #5:#6
        d.writeShort(5)
        d.writeShort(6)
        d.writeByte(10) // #8 Methodref -> #3.#7
        d.writeShort(3)
        d.writeShort(7)
        d.writeByte(1) // #9 Utf8 Code
        d.writeUTF("Code")
        d.writeByte(1) // #10 Utf8 (spare entry, keeps the pool count simple)
        d.writeUTF("NettyNamedTimeout")
        // Class
        d.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
        d.writeShort(1) // this
        d.writeShort(3) // super
        d.writeShort(0) // interfaces
        d.writeShort(0) // fields
        d.writeShort(1) // methods
        // Method <init>
        d.writeShort(0x0001) // ACC_PUBLIC
        d.writeShort(5) // name
        d.writeShort(6) // descriptor
        d.writeShort(1) // attributes
        d.writeShort(9) // Code
        val code = byteArrayOf(0x2a, 0xb7.toByte(), 0x00, 0x08, 0xb1.toByte()) // aload_0; invokespecial #8; return
        d.writeInt(2 + 2 + 4 + code.size + 2 + 2) // attribute length
        d.writeShort(1) // max stack
        d.writeShort(1) // max locals
        d.writeInt(code.size)
        d.write(code)
        d.writeShort(0) // exception table
        d.writeShort(0) // code attributes
        d.writeShort(0) // class attributes
        d.flush()
        return out.toByteArray()
    }
}
