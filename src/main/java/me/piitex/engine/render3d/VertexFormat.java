package me.piitex.engine.render3d;

public enum VertexFormat {
    FLOAT1(1), FLOAT2(2), FLOAT3(3), FLOAT4(4);

    public final int components;

    VertexFormat(int components) {
        this.components = components;
    }

    public int byteSize() {
        return components * 4;
    }
}
