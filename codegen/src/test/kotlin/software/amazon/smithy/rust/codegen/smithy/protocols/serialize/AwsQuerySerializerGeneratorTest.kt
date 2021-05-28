package software.amazon.smithy.rust.codegen.smithy.protocols.serialize

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.smithy.generators.EnumGenerator
import software.amazon.smithy.rust.codegen.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.smithy.transformers.RecursiveShapeBoxer
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.renderWithModelBuilder
import software.amazon.smithy.rust.codegen.testutil.testProtocolConfig
import software.amazon.smithy.rust.codegen.testutil.testSymbolProvider
import software.amazon.smithy.rust.codegen.testutil.unitTest
import software.amazon.smithy.rust.codegen.util.expectTrait
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.lookup

class AwsQuerySerializerGeneratorTest {
    private val baseModel = """
        namespace test
        use aws.protocols#restJson1

        union Choice {
            blob: Blob,
            boolean: Boolean,
            date: Timestamp,
            enum: FooEnum,
            int: Integer,
            @xmlFlattened
            list: SomeList,
            long: Long,
            map: MyMap,
            number: Double,
            s: String,
            top: Top,
        }

        @enum([{name: "FOO", value: "FOO"}])
        string FooEnum

        map MyMap {
            key: String,
            value: Choice,
        }

        list SomeList {
            member: Choice
        }

        structure Top {
            choice: Choice,
            field: String,
            extra: Long,
            @xmlName("rec")
            recursive: TopList
        }

        list TopList {
            @xmlName("item")
            member: Top
        }

        structure OpInput {
            @xmlName("some_bool")
            boolean: Boolean,
            list: SomeList,
            map: MyMap,
            top: Top,
        }

        @http(uri: "/top", method: "POST")
        operation Op {
            input: OpInput,
        }
    """.asSmithyModel()

    @Test
    fun `generates valid serializers`() {
        val model = RecursiveShapeBoxer.transform(
            OperationNormalizer(baseModel).transformModel(
                OperationNormalizer.NoBody,
                OperationNormalizer.NoBody
            )
        )
        val symbolProvider = testSymbolProvider(model)
        val parserGenerator = AwsQuerySerializerGenerator(testProtocolConfig(model))
        val operationGenerator = parserGenerator.operationSerializer(model.lookup("test#Op"))

        val project = TestWorkspace.testProject(testSymbolProvider(model))
        project.lib { writer ->
            writer.unitTest(
                """
                use model::Top;

                let input = crate::input::OpInput::builder()
                    .top(
                        Top::builder()
                            .field("hello!")
                            .extra(45)
                            .recursive(Top::builder().extra(55).build())
                            .build()
                    )
                    .boolean(true)
                    .build()
                    .unwrap();
                let serialized = ${writer.format(operationGenerator!!)}(&input).unwrap();
                let output = std::str::from_utf8(serialized.bytes().unwrap()).unwrap();
                assert_eq!(
                    output,
                    "\
                    Action=Op\
                    \n&Version=test\
                    \n&some_bool=true\
                    \n&top.field=hello%21\
                    \n&top.extra=45\
                    \n&top.rec.item.1.extra=55\
                    "
                );
                """
            )
        }
        project.withModule(RustModule.default("model", public = true)) {
            model.lookup<StructureShape>("test#Top").renderWithModelBuilder(model, symbolProvider, it)
            UnionGenerator(model, symbolProvider, it, model.lookup("test#Choice")).render()
            val enum = model.lookup<StringShape>("test#FooEnum")
            EnumGenerator(model, symbolProvider, it, enum, enum.expectTrait()).render()
        }

        project.withModule(RustModule.default("input", public = true)) {
            model.lookup<OperationShape>("test#Op").inputShape(model).renderWithModelBuilder(model, symbolProvider, it)
        }
        println("file:///${project.baseDir}/src/lib.rs")
        println("file:///${project.baseDir}/src/model.rs")
        println("file:///${project.baseDir}/src/operation_ser.rs")
        println("file:///${project.baseDir}/src/query_ser.rs")
        project.compileAndTest()
    }
}