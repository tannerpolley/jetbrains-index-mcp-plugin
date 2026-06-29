package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.cpp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import junit.framework.TestCase

class CppSourceStructureParserUnitTest : TestCase() {

    fun testParsesSmokeSourceStructure() {
        val source = """
            #include <iostream>

            namespace smoke {
            class Counter {
            public:
                explicit Counter(int start) : value(start) {}

                int increment() {
                    return ++value;
                }

            private:
                int value;
            };
            }

            int main() {
                smoke::Counter counter(41);
                std::cout << counter.increment() << std::endl;
                return 0;
            }
        """.trimIndent()

        val nodes = CppSourceStructureParser.parse(source)

        assertEquals(3, nodes.size)
        assertEquals(StructureKind.INCLUDE, nodes[0].kind)
        assertEquals("<iostream>", nodes[0].name)

        val namespace = nodes[1]
        assertEquals(StructureKind.NAMESPACE, namespace.kind)
        assertEquals("smoke", namespace.name)

        val counter = namespace.children.single()
        assertEquals(StructureKind.CLASS, counter.kind)
        assertEquals("Counter", counter.name)

        assertEquals(
            listOf(
                StructureKind.CONSTRUCTOR,
                StructureKind.METHOD,
                StructureKind.FIELD
            ),
            counter.children.map { it.kind }
        )
        assertEquals("Counter", counter.children[0].name)
        assertEquals("increment", counter.children[1].name)
        assertEquals("value", counter.children[2].name)

        assertEquals(StructureKind.FUNCTION, nodes[2].kind)
        assertEquals("main", nodes[2].name)
    }

    fun testParsesTopLevelFunctionAfterNamespace() {
        val source = """
            namespace demo {
            struct Value {
                int amount;
            };
            }

            int main() {
                return 0;
            }
        """.trimIndent()

        val nodes = CppSourceStructureParser.parse(source)

        assertEquals(2, nodes.size)
        assertEquals(StructureKind.NAMESPACE, nodes[0].kind)
        assertEquals(StructureKind.FUNCTION, nodes[1].kind)
        assertEquals("main", nodes[1].name)
    }
}
